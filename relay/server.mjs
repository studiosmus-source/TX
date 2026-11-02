import http from 'node:http';
import { createHash, createHmac, randomUUID, randomBytes, timingSafeEqual } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const MAX_REQUEST_BYTES = 4096;
const MAX_RESULT_BYTES = 160_000;
const MAX_PENDING = 16;
const TTL_MS = 120_000;
const PAIR_TTL_MS = 600_000;
const MAX_PAIR_REQUESTS = 4;
const MAX_PAIR_PER_MINUTE = 8;

function equalsToken(a, b) {
  const hash = x => createHash('sha256').update(x).digest();
  return timingSafeEqual(hash(a), hash(b));
}

function send(res, code, payload) {
  if (res.writableEnded || res.destroyed) return;
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
  res.end(JSON.stringify(payload));
}

function receive(req, max) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.setEncoding('utf8');
    req.on('data', chunk => {
      body += chunk;
      if (Buffer.byteLength(body) > max) {
        reject(new Error('body_too_large'));
        req.destroy();
      }
    });
    req.on('end', () => {
      try { resolve(JSON.parse(body)); } catch { reject(new Error('invalid_json')); }
    });
    req.on('error', reject);
  });
}

/** Multi-device relay. TX credentials are derived from a server secret and a unique device ID.
 * Device identity survives a relay restart without persisting secrets on the server.
 * Active device listing is an ephemeral presence directory refreshed by TX polling.
 */
export function createRelay({ txToken, rxToken, requireTls = true } = {}) {
  if (typeof txToken !== 'string' || typeof rxToken !== 'string' || txToken.length < 32 || rxToken.length < 32 || equalsToken(txToken, rxToken)) {
    throw new Error('Configure two different random tokens (at least 32 chars each)');
  }
  const pending = new Map();
  const pairing = new Map();
  const devices = new Map();
  const validId = id => typeof id === 'string' &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(id);
  const deviceSecret = id => createHmac('sha256', txToken).update('gallery-device-v1:' + id).digest('hex');
  const ACTIVE_MS = 70_000;
  let pairWindowStart = Date.now();
  let pairRequestsInWindow = 0;
  const pairHtml = readFileSync(new URL('./pair.html', import.meta.url));
  const pairJs = readFileSync(new URL('./pair.js', import.meta.url));
  const waitingTx = new Set();
  const purgeTimer = setInterval(() => {
    const now = Date.now();
    for (const [id, entry] of pairing) if (now - entry.created > PAIR_TTL_MS) pairing.delete(id);
    for (const [id, item] of devices) if (now - item.lastSeen > 7 * 24 * 3600_000) devices.delete(id);
    for (const [id, job] of pending) {
      if (now - job.created > TTL_MS) {
        pending.delete(id);
        for (const response of job.waitingRx) send(response, 410, { error: 'expired' });
      }
    }
  }, 10_000);
  purgeTimer.unref();

  function nextCommand(deviceId) {
    for (const job of pending.values()) {
      if (job.state === 'queued' && job.deviceId === deviceId) {
        job.state = 'sent';
        return { id: job.id, ...job.command };
      }
    }
    return null;
  }

  function dispatch() {
    for (const waiter of [...waitingTx]) {
      const command = nextCommand(waiter.deviceId);
      if (!command) continue;
      waitingTx.delete(waiter);
      clearTimeout(waiter.timer);
      send(waiter.res, 200, command);
    }
  }

  const server = http.createServer(async (req, res) => {
    const pathname = new URL(req.url || '/', 'http://relay.local').pathname;
    if (pathname === '/health' && req.method === 'GET') return send(res, 200, { status: 'ok' });
    // The managed reverse proxy must overwrite x-forwarded-proto.
    if (requireTls && !req.socket.encrypted && req.headers['x-forwarded-proto'] !== 'https') return send(res, 403, { error: 'https_required' });
    // The enrollment page contains no secrets. The JSON APIs require RX credentials.
    if (pathname === '/pair' && req.method === 'GET') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store',
        'Content-Security-Policy': "default-src 'none'; connect-src 'self'; script-src 'self'; style-src 'unsafe-inline'", 'X-Content-Type-Options': 'nosniff' });
      return res.end(pairHtml);
    }
    if (pathname === '/pair.js' && req.method === 'GET') {
      res.writeHead(200, { 'Content-Type': 'application/javascript; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
      return res.end(pairJs);
    }
    const auth = req.headers.authorization || '';
    const role = typeof auth === 'string' && auth.startsWith('Bearer ') ? auth.slice(7) : '';
    const deviceId = req.headers['x-device-id'];
    const isDevice = validId(deviceId) && equalsToken(role, deviceSecret(deviceId));
    const isRx = equalsToken(role, rxToken);
    if (!isDevice && !isRx && pathname !== '/v1/pair/request' && pathname !== '/v1/pair/poll')
      return send(res, 401, { error: 'unauthorized' });

    try {
      // A new TX cannot access files until RX approves its one-time enrollment request.
      if (pathname === '/v1/pair/request' && req.method === 'POST') {
        const now = Date.now();
        if (now - pairWindowStart > 60_000) { pairWindowStart = now; pairRequestsInWindow = 0; }
        if (++pairRequestsInWindow > MAX_PAIR_PER_MINUTE || pairing.size >= MAX_PAIR_REQUESTS)
          return send(res, 429, { error: 'too_many_requests' });
        const obj = await receive(req, MAX_REQUEST_BYTES);
        if (!obj || typeof obj.name !== 'string' || obj.name.length < 1 || obj.name.length > 60 || /[\x00-\x1f]/.test(obj.name))
          return send(res, 400, { error: 'invalid_request' });
        const id = randomUUID();
        const claim = randomBytes(32).toString('hex');
        const code = String(randomBytes(4).readUInt32BE() % 1_000_000).padStart(6, '0');
        pairing.set(id, { id, claimHash: createHash('sha256').update(claim).digest('hex'),
          code, name: obj.name, created: now, approved: false });
        return send(res, 201, { id, claim, code, expiresInSeconds: PAIR_TTL_MS / 1000 });
      }
      if (pathname === '/v1/pair/poll' && req.method === 'POST') {
        const obj = await receive(req, MAX_REQUEST_BYTES);
        const p = pairing.get(obj?.id);
        if (!p || typeof obj?.claim !== 'string' ||
            !equalsToken(createHash('sha256').update(obj.claim).digest('hex'), p.claimHash))
          return send(res, 404, { error: 'not_found' });
        if (Date.now() - p.created > PAIR_TTL_MS) { pairing.delete(p.id); return send(res, 410, { error: 'expired' }); }
        if (!p.approved) return send(res, 202, { pending: true });
        pairing.delete(p.id);
        return send(res, 200, {
          approved: true, deviceId: p.deviceId, deviceToken: deviceSecret(p.deviceId)
        });
      }
      if (pathname === '/v1/pair/pending' && req.method === 'GET' && isRx) {
        return send(res, 200, { requests: [...pairing.values()].filter(p=>!p.approved)
          .map(p=>({id:p.id, name:p.name, code:p.code, created:p.created})) });
      }
      const pairApprove = /^\/v1\/pair\/approve\/([0-9a-f-]{36})$/.exec(pathname);
      if (pairApprove && req.method === 'POST' && isRx) {
        const p = pairing.get(pairApprove[1]);
        if (!p || p.approved || Date.now()-p.created > PAIR_TTL_MS) return send(res, 404, {error:'not_found'});
        const obj = await receive(req, MAX_REQUEST_BYTES);
        if (!obj || typeof obj.txToken !== 'string' || !equalsToken(obj.txToken, txToken))
          return send(res, 403, { error: 'invalid_tx_key' });
        p.deviceId = randomUUID();
        p.approved = true;
        return send(res, 200, { approved: true, deviceId: p.deviceId, name: p.name });
      }
      if (pathname === '/v1/tx/next' && req.method === 'GET' && isDevice) {
        const rawName = req.headers['x-device-name'];
        const name = typeof rawName === 'string' && rawName.length >= 1 && rawName.length <= 60 &&
          !/[\x00-\x1f]/.test(rawName) ? rawName : 'GALLERY';
        devices.set(deviceId, { id: deviceId, name, lastSeen: Date.now() });
        const available = nextCommand(deviceId);
        if (available) return send(res, 200, available);
        const waiter = { res, deviceId, timer: null };
        waiter.timer = setTimeout(() => {
          waitingTx.delete(waiter);
          send(res, 200, { pending: true });
        }, 23_000);
        waitingTx.add(waiter);
        res.once('close', () => { waitingTx.delete(waiter); clearTimeout(waiter.timer); });
        return;
      }
      if (pathname === '/v1/rx/devices' && req.method === 'GET' && isRx) {
        return send(res, 200, { devices: [...devices.values()].map(d => ({
          id: d.id, name: d.name, online: Date.now() - d.lastSeen < ACTIVE_MS
        })).sort((a,b) => a.name.localeCompare(b.name)) });
      }
      if (pathname === '/v1/rx/request' && req.method === 'POST' && isRx) {
        if (pending.size >= MAX_PENDING) return send(res, 429, { error: 'busy' });
        const obj = await receive(req, MAX_REQUEST_BYTES);
        if (!obj || !validId(obj.deviceId) ||
            !['list', 'read', 'gallery', 'thumb', 'preview'].includes(obj.op) || typeof obj.path !== 'string' || obj.path.length > 1024 || obj.path.includes('\0') ||
            !Number.isSafeInteger(obj.offset) || obj.offset < 0 ||
            ((obj.op === 'list' || obj.op === 'gallery') && (!Number.isSafeInteger(obj.limit) || obj.limit < 1 || obj.limit > (obj.op === 'gallery' ? 80 : 200))) ||
            (obj.op === 'read' && (!Number.isSafeInteger(obj.length) || obj.length < 1 || obj.length > 65_536))) {
          return send(res, 400, { error: 'invalid_request' });
        }
        const known = devices.get(obj.deviceId);
        if (!known || Date.now() - known.lastSeen > ACTIVE_MS)
          return send(res, 409, { error: 'device_offline' });
        const id = randomUUID();
        const job = { id, deviceId: obj.deviceId,
          command: { op: obj.op, path: obj.path, offset: obj.offset,
            ...(['list', 'gallery'].includes(obj.op) ? { limit: obj.limit } :
              obj.op === 'read' ? { length: obj.length } : {}) },
          state: 'queued', created: Date.now(), waitingRx: new Set(), result: null };
        pending.set(id, job);
        send(res, 202, { id });
        dispatch();
        return;
      }
      const txResult = /^\/v1\/tx\/result\/([0-9a-f-]{36})$/.exec(pathname);
      if (txResult && req.method === 'POST' && isDevice) {
        const job = pending.get(txResult[1]);
        if (!job || job.deviceId !== deviceId || job.state !== 'sent') return send(res, 404, { error: 'missing' });
        const data = await receive(req, MAX_RESULT_BYTES);
        if (!data || typeof data.ok !== 'boolean') return send(res, 400, { error: 'invalid_response' });
        job.result = data;
        job.state = 'done';
        for (const waiter of job.waitingRx) send(waiter, 200, data);
        job.waitingRx.clear();
        return send(res, 200, { delivered: true });
      }
      const rxResult = /^\/v1\/rx\/result\/([0-9a-f-]{36})$/.exec(pathname);
      if (rxResult && req.method === 'GET' && isRx) {
        const job = pending.get(rxResult[1]);
        if (!job) return send(res, 404, { error: 'missing' });
        if (job.state === 'done') {
          pending.delete(job.id);
          return send(res, 200, job.result);
        }
        const timer = setTimeout(() => {
          job.waitingRx.delete(res);
          send(res, 202, { pending: true });
        }, 23_000);
        job.waitingRx.add(res);
        res.once('close', () => { job.waitingRx.delete(res); clearTimeout(timer); });
        return;
      }
      return send(res, 404, { error: 'not_found' });
    } catch (error) {
      if (!res.headersSent && !res.destroyed) send(res, 400, { error: error?.message || 'bad_request' });
    }
  });
  server.on('close', () => {
    clearInterval(purgeTimer);
    for (const waiter of waitingTx) { clearTimeout(waiter.timer); send(waiter.res, 503, { error: 'shutdown' }); }
    for (const job of pending.values()) for (const response of job.waitingRx) send(response, 503, { error: 'shutdown' });
    waitingTx.clear(); pending.clear(); pairing.clear(); devices.clear();
  });
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const server = createRelay({ txToken: process.env.TX_TOKEN, rxToken: process.env.RX_TOKEN,
    requireTls: process.env.LOCAL_TEST_ONLY !== 'true' });
  const port = Number(process.env.PORT || 8080);
  server.listen(port, '0.0.0.0', () => console.log(`TX relay listening on ${port}`));
}
