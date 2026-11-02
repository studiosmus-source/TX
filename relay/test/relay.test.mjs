import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRelay } from '../server.mjs';

const txToken = 'TX-token-for-tests-12345678901234567890123';
const rxToken = 'RX-token-for-tests-12345678901234567890123';

async function withRelay(fn) {
  const relay = createRelay({ txToken, rxToken, requireTls: false });
  await new Promise(resolve => relay.listen(0, '127.0.0.1', resolve));
  try { await fn(`http://127.0.0.1:${relay.address().port}`); }
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
  res = await access(base + '/v1/rx/request', rxToken, 'POST', { op: 'read', path: 'photo.jpg', offset: 0, length: 70_000 });
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
