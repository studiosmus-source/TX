import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRelay } from '../server.mjs';

const txToken = 'TX-token-for-tests-12345678901234567890123';
const rxToken = 'RX-token-for-tests-12345678901234567890123';

async function withRelay(fn) {
  const relay = createRelay({ txToken, rxToken, requireTls: false });
  await new Promise(resolve => relay.listen(0, '127.0.0.1', resolve));
  try { await fn(`http://127.0.0.1:${relay.address().port}`, relay); }
  finally { relay.closeAllConnections(); await new Promise(resolve => relay.close(resolve)); }
}

const access = (url, token, method = 'GET', body) => fetch(url, {
  method,
  headers: { Authorization: `Bearer ${token}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
  body: body ? JSON.stringify(body) : undefined,
});

test('only correct role/token may submit or retrieve requests', async () => withRelay(async base => {
  let res = await access(base + '/v1/rx/request', 'incorrect-token', 'POST', { op: 'list', path: '', offset: 0, limit: 20 });
  assert.equal(res.status, 401);
  res = await access(base + '/v1/rx/request', txToken, 'POST', { op: 'list', path: '', offset: 0, limit: 20 });
  assert.equal(res.status, 404);
  res = await access(base + '/v1/rx/request', rxToken, 'POST', { op: 'read', path: 'photo.jpg', offset: 0, length: 524_289 });
  assert.equal(res.status, 400);
}));

test('a single file block moves from RX request through TX to RX result', async () => withRelay(async base => {
  const next = access(base + '/v1/tx/next', txToken);
  const request = await access(base + '/v1/rx/request', rxToken, 'POST', { op: 'read', path: 'DCIM/photo.jpg', offset: 0, length: 12 });
  assert.equal(request.status, 202);
  const id = (await request.json()).id;
  const cmdRes = await next;
  assert.equal(cmdRes.status, 200);
  const cmd = await cmdRes.json();
  assert.equal(cmd.id, id);
  assert.deepEqual([cmd.op, cmd.path, cmd.length], ['read', 'DCIM/photo.jpg', 12]);
  const result = await access(base + '/v1/tx/result/' + id, txToken, 'POST', {
    ok: true, base64: Buffer.from('hello').toString('base64'), nextOffset: 5, totalBytes: 5, complete: true
  });
  assert.equal(result.status, 200);
  const actual = await access(base + '/v1/rx/result/' + id, rxToken);
  assert.equal(actual.status, 200);
  const data = await actual.json();
  assert.equal(Buffer.from(data.base64, 'base64').toString(), 'hello');
  const replay = await access(base + '/v1/rx/result/' + id, rxToken);
  assert.equal(replay.status, 404);
}));

test('TLS can be enforced and health does not require credentials', async () => {
  const relay = createRelay({ txToken, rxToken, requireTls: true });
  await new Promise(resolve => relay.listen(0, '127.0.0.1', resolve));
  try {
    const base = `http://127.0.0.1:${relay.address().port}`;
    const health = await fetch(base + '/health');
    assert.equal(health.status, 200);
    const resp = await access(base + '/v1/tx/next', txToken);
    assert.equal(resp.status, 403);
  } finally { relay.closeAllConnections(); await new Promise(resolve => relay.close(resolve)); }
});

test('TX result endpoint rejects RX credentials and malformed answers', async () => withRelay(async base => {
  const request = await access(base + '/v1/rx/request', rxToken, 'POST', { op: 'list', path: '', offset: 0, limit: 10 });
  assert.equal(request.status, 202);
  const { id } = await request.json();
  const next = await access(base + '/v1/tx/next', txToken);
  assert.equal((await next.json()).id, id);
  const forbidden = await access(base + '/v1/tx/result/' + id, rxToken, 'POST', { ok: true, entries: [] });
  assert.equal(forbidden.status, 404);
  const invalid = await access(base + '/v1/tx/result/' + id, txToken, 'POST', { entries: [] });
  assert.equal(invalid.status, 400);
  const result = await access(base + '/v1/tx/result/' + id, txToken, 'POST', { ok: true, entries: [], total: 0, hasMore: false });
  assert.equal(result.status, 200);
}));

test('a transmitter receives its TX key only after RX approves its enrollment', async () => withRelay(async base => {
  const init = await fetch(base + '/v1/pair/request', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: 'Test TX phone' }),
  });
  assert.equal(init.status, 201);
  const { id, claim, code } = await init.json();
  assert.equal(code.length, 6);
  const poll = async (secret) => fetch(base + '/v1/pair/poll', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id, claim: secret }),
  });
  let res = await poll(claim);
  assert.equal(res.status, 202);
  assert.deepEqual(await res.json(), { pending: true });
  res = await poll('wrong-claim');
  assert.equal(res.status, 404);
  res = await access(base + '/v1/pair/pending', txToken);
  assert.equal(res.status, 404);
  res = await access(base + '/v1/pair/pending', rxToken);
  assert.equal(res.status, 200);
  assert.equal((await res.json()).requests[0].code, code);
  res = await access(base + '/v1/pair/approve/' + id, rxToken, 'POST', { txToken: 'wrong-tx-token' });
  assert.equal(res.status, 403);
  res = await access(base + '/v1/pair/approve/' + id, txToken, 'POST', { txToken });
  assert.equal(res.status, 404);
  res = await access(base + '/v1/pair/approve/' + id, rxToken, 'POST', { txToken });
  assert.equal(res.status, 200);
  res = await poll(claim);
  assert.equal(res.status, 200);
  assert.equal((await res.json()).txToken, txToken);
  res = await poll(claim);
  assert.equal(res.status, 404);
}));

test('pairing limits and invalid device names are enforced', async () => withRelay(async base => {
  let res = await fetch(base + '/v1/pair/request', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: 'bad\nname' }),
  });
  assert.equal(res.status, 400);
  for (let i = 0; i < 3; i++) {
    res = await fetch(base + '/v1/pair/request', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Another TX' }),
    });
    assert.equal(res.status, 201);
  }
  res = await access(base + '/v1/pair/pending', rxToken);
  assert.equal((await res.json()).requests.length, 3);
}));

test('HTTPS restriction also covers pairing and its browser page', async () => {
  const relay = createRelay({ txToken, rxToken, requireTls: true });
  await new Promise(resolve => relay.listen(0, '127.0.0.1', resolve));
  try {
    const base = `http://127.0.0.1:${relay.address().port}`;
    const page = await fetch(base + '/pair');
    assert.equal(page.status, 403);
    const request = await fetch(base + '/v1/pair/request', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: 'Test device' }) });
    assert.equal(request.status, 403);
  } finally { relay.closeAllConnections(); await new Promise(resolve => relay.close(resolve)); }
});


test('RX gallery and preview are authorized, bounded and routed to TX', async () => withRelay(async base => {
  const invalid = await access(base + '/v1/rx/request', rxToken, 'POST',
    {op:'gallery',path:'',offset:0,limit:100});
  assert.equal(invalid.status,400);
  const unauthorized = await access(base + '/v1/rx/request','wrong','POST',
    {op:'gallery',path:'',offset:0,limit:30});
  assert.equal(unauthorized.status,401);
  for (const [command,answer] of [
    [{op:'gallery',path:'',offset:0,limit:30},{ok:true,entries:[],nextOffset:0,hasMore:false}],
    [{op:'thumb',path:'DCIM/a.jpg',offset:0},{ok:true,base64:'ZmFrZQ=='}],
    [{op:'preview',path:'DCIM/a.jpg',offset:0},{ok:true,base64:'ZmFrZQ=='}],
  ]) {
    const request = await access(base+'/v1/rx/request',rxToken,'POST',command);
    assert.equal(request.status,202);
    const {id}=await request.json();
    const next=await access(base+'/v1/tx/next',txToken);
    const sent=await next.json();
    assert.equal(sent.op,command.op);
    assert.equal(sent.id,id);
    const posted=await access(base+'/v1/tx/result/'+id,txToken,'POST',answer);
    assert.equal(posted.status,200);
    const result=await access(base+'/v1/rx/result/'+id,rxToken);
    assert.equal(result.status,200);
    assert.deepEqual(await result.json(),answer);
  }
}));


test('16 completed long-poll thumbnails do not exhaust the queue before download', async () =>
  withRelay(async (base, relay) => {
    for (let i = 0; i < 20; i++) {
      const txNext = access(base + '/v1/tx/next', txToken);
      const command = i === 19
        ? { op: 'read', path: 'DCIM/original.jpg', offset: 0, length: 5 }
        : { op: 'thumb', path: 'DCIM/image-' + i + '.jpg', offset: 0 };
      const created = await access(base + '/v1/rx/request', rxToken, 'POST', command);
      assert.equal(created.status, 202, 'request ' + i + ' must not see busy');
      const { id } = await created.json();
      const txResult = await txNext;
      assert.equal(txResult.status, 200);
      assert.equal((await txResult.json()).id, id);

      // Observe the incoming RX long-poll on the server, not just the client
      // call. This forces coverage of the direct waiter delivery branch.
      const waiterArrived = new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          relay.off('request', onRequest);
          reject(new Error('RX long-poll did not arrive'));
        }, 3000);
        function onRequest(req) {
          if (req.method === 'GET' && req.url === '/v1/rx/result/' + id) {
            clearTimeout(timer);
            relay.off('request', onRequest);
            resolve();
          }
        }
        relay.on('request', onRequest);
      });
      const waitingRx = access(base + '/v1/rx/result/' + id, rxToken);
      await waiterArrived;

      const data = { ok: true, base64: Buffer.from('hello').toString('base64'),
        totalBytes: 5, nextOffset: 5, complete: true };
      const posted = await access(base + '/v1/tx/result/' + id, txToken, 'POST', data);
      assert.equal(posted.status, 200);
      const delivered = await waitingRx;
      assert.equal(delivered.status, 200);
      assert.deepEqual(await delivered.json(), data);

      // The waiter consumed the result: it must no longer count as pending.
      const alreadyDelivered = await access(base + '/v1/rx/result/' + id, rxToken);
      assert.equal(alreadyDelivered.status, 404);
    }
  })
);

test('large binary 512 KiB read remains byte-exact and authenticated', async () =>
  withRelay(async base => {
    const original = Buffer.alloc(512 * 1024);
    for (let i = 0; i < original.length; i++) original[i] = i % 251;
    const next = access(base + '/v1/tx/next', txToken);
    const requested = await access(base + '/v1/rx/request', rxToken, 'POST',
      { op: 'read', path: 'DCIM/large.jpg', offset: 0, length: original.length });
    assert.equal(requested.status, 202);
    const { id } = await requested.json();
    const incoming = await next;
    assert.equal(incoming.status, 200);
    const command = await incoming.json();
    assert.equal(command.id, id);
    assert.equal(command.length, 512 * 1024);
    const posted = await access(base + '/v1/tx/result/' + id, txToken, 'POST', {
      ok: true,
      base64: original.toString('base64'),
      nextOffset: original.length,
      totalBytes: original.length,
      complete: true,
    });
    assert.equal(posted.status, 200);
    const response = await access(base + '/v1/rx/result/' + id, rxToken);
    assert.equal(response.status, 200);
    const data = await response.json();
    assert.deepEqual(Buffer.from(data.base64, 'base64'), original);
  })
);
