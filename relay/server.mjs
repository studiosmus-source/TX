import http from 'node:http';
import { createHash, randomUUID, timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';

const MAX_REQUEST_BYTES = 4096;
const MAX_RESULT_BYTES = 160_000;
const MAX_PENDING = 16;
const TTL_MS = 120_000;

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

/** In-memory, single pair, no disk storage. Protected by distinct 32+ char tokens. */
export function createRelay({ txToken, rxToken, requireTls = true } = {}) {
  if (typeof txToken !== 'string' || typeof rxToken !== 'string' || txToken.length < 32 || rxToken.length < 32 || equalsToken(txToken, rxToken)) {
    throw new Error('Configure two different random tokens (at least 32 chars each)');
  }
  const pending = new Map();
  const waitingTx = new Set();
  const purgeTimer = setInterval(() => {
    const now = Date.now();
    for (const [id, job] of pending) {
      if (now - job.created > TTL_MS) {
        pending.delete(id);
        for (const response of job.waitingRx) send(response, 410, { error: 'expired' });
      }
    }
  }, 10_000);
  purgeTimer.unref();

  function nextCommand() {
    for (const job of pending.values()) {
      if (job.state === 'queued') {
        job.state = 'sent';
        return { id: job.id, ...job.command };
      }
    }
    return null;
  }

  function dispatch() {
    while (waitingTx.size) {
      const command = nextCommand();
      if (!command) return;
      const waiter = waitingTx.values().next().value;
      waitingTx.delete(waiter);
      clearTimeout(waiter.timer);
      send(waiter.res, 200, command);
    }
  }

  const server = http.createServer(async (req, res) => {
    const pathname = new URL(req.url || '/', 'http://relay.local').pathname;
    if (pathname === '/health' && req.method === 'GET') return send(res, 200, { status: 'ok' });
    // When deployed behind Render/another managed TLS reverse proxy, it must SET
    // and overwrite x-forwarded-proto. Do not expose the raw HTTP port publicly.
    if (requireTls && !req.socket.encrypted && req.headers['x-forwarded-proto'] !== 'https') return send(res, 403, { error: 'https_required' });
    const auth = req.headers.authorization || '';
    const role = typeof auth === 'string' && auth.startsWith('Bearer ') ? auth.slice(7) : '';
    const isTx = equalsToken(role, txToken);
    const isRx = equalsToken(role, rxToken);
    if (!isTx && !isRx) return send(res, 401, { error: 'unauthorized' });

    try {
      if (pathname === '/v1/tx/next' && req.method === 'GET' && isTx) {
        const available = nextCommand();
        if (available) return send(res, 200, available);
        const waiter = { res, timer: null };
        waiter.timer = setTimeout(() => {
          waitingTx.delete(waiter);
          send(res, 200, { pending: true });
        }, 23_000);
        waitingTx.add(waiter);
        res.once('close', () => { waitingTx.delete(waiter); clearTimeout(waiter.timer); });
        return;
      }
      if (pathname === '/v1/rx/request' && req.method === 'POST' && isRx) {
        if (pending.size >= MAX_PENDING) return send(res, 429, { error: 'busy' });
        const obj = await receive(req, MAX_REQUEST_BYTES);
        if (!obj || !['list', 'read'].includes(obj.op) || typeof obj.path !== 'string' || obj.path.length > 1024 || obj.path.includes('\0') ||
            !Number.isSafeInteger(obj.offset) || obj.offset < 0 ||
            (obj.op === 'list' && (!Number.isSafeInteger(obj.limit) || obj.limit < 1 || obj.limit > 200)) ||
            (obj.op === 'read' && (!Number.isSafeInteger(obj.length) || obj.length < 1 || obj.length > 65_536))) {
          return send(res, 400, { error: 'invalid_request' });
        }
        const id = randomUUID();
        const job = { id, command: { op: obj.op, path: obj.path, offset: obj.offset, ...(obj.op === 'list' ? { limit: obj.limit } : { length: obj.length }) }, state: 'queued', created: Date.now(), waitingRx: new Set(), result: null };
        pending.set(id, job);
        send(res, 202, { id });
        dispatch();
        return;
      }
      const txResult = /^\/v1\/tx\/result\/([0-9a-f-]{36})$/.exec(pathname);
      if (txResult && req.method === 'POST' && isTx) {
        const job = pending.get(txResult[1]);
        if (!job || job.state !== 'sent') return send(res, 404, { error: 'missing' });
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
    waitingTx.clear(); pending.clear();
  });
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const server = createRelay({ txToken: process.env.TX_TOKEN, rxToken: process.env.RX_TOKEN,
    requireTls: process.env.LOCAL_TEST_ONLY !== 'true' });
  const port = Number(process.env.PORT || 8080);
  server.listen(port, '0.0.0.0', () => console.log(`TX relay listening on ${port}`));
}
