import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { createRelay } from '../server.mjs';

const rootTxToken = 'TX-token-for-tests-12345678901234567890123';
const deviceId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const device2Id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const deviceToken = id => createHmac('sha256', rootTxToken)
  .update('gallery-device-v1:' + id).digest('hex');
const txToken = deviceToken(deviceId);
const txToken2 = deviceToken(device2Id);
const rxToken = 'RX-token-for-tests-12345678901234567890123';

async function withRelay(fn) {
  const relay = createRelay({ txToken: rootTxToken, rxToken, requireTls: false });
  await new Promise(resolve => relay.listen(0, '127.0.0.1', resolve));
  try { await fn(`http://127.0.0.1:${relay.address().port}`); }
  finally { relay.closeAllConnections(); await new Promise(resolve => relay.close(resolve)); }
}

const access = (url, token, method = 'GET', body) => fetch(url, {
  method,
  headers: { Authorization: `Bearer ${token}`,
    ...(token === txToken || token === txToken2 ? {
      'X-Device-Id': token === txToken ? deviceId : device2Id,
      'X-Device-Name': token === txToken ? 'Gallery One' : 'Gallery Two'
    } : {}),
    ...(body ? { 'Content-Type': 'application/json' } : {}) },
  body: body ? JSON.stringify(body) : undefined,
});

test('only correct role/token may submit or retrieve requests', async () => withRelay(async base => {
  let res = await access(base + '/v1/rx/request', 'incorrect-token', 'POST', { op: 'list', path: '', offset: 0, limit: 20 });
  assert.equal(res.status, 401);
  res = await access(base + '/v1/rx/request', txToken, 'POST', { op: 'list', path: '', offset: 0, limit: 20 });
  assert.equal(res.status, 404);
  res = await access(base + '/v1/rx/request', rxToken, 'POST', { deviceId, op: 'read', path: 'photo.jpg', offset: 0, length: 70_000 });
  assert.equal(res.status, 400);
}));

test('a single file block moves from RX request through TX to RX result', async () => withRelay(async base => {
  const next = access(base + '/v1/tx/next', txToken);
  const request = await access(base + '/v1/rx/request', rxToken, 'POST', { deviceId, op: 'read', path: 'DCIM/photo.jpg', offset: 0, length: 12 });
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
  const relay = createRelay({ txToken: rootTxToken, rxToken, requireTls: true });
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
  const nextPromise = access(base + '/v1/tx/next', txToken);
  await new Promise(resolve => setTimeout(resolve, 20));
  const request = await access(base + '/v1/rx/request', rxToken, 'POST', { deviceId, op: 'list', path: '', offset: 0, limit: 10 });
  assert.equal(request.status, 202);
  const { id } = await request.json();
  const next = await nextPromise;
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
  res = await access(base + '/v1/pair/approve/' + id, txToken, 'POST', { txToken: rootTxToken });
  assert.equal(res.status, 404);
  res = await access(base + '/v1/pair/approve/' + id, rxToken, 'POST', { txToken: rootTxToken });
  assert.equal(res.status, 200);
  res = await poll(claim);
  assert.equal(res.status, 200);
  const approved = await res.json();
  assert.equal(approved.approved, true);
  assert.match(approved.deviceId, /^[0-9a-f-]{36}$/);
  assert.equal(approved.deviceToken, deviceToken(approved.deviceId));
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
  const relay = createRelay({ txToken: rootTxToken, rxToken, requireTls: true });
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
    {deviceId,op:'gallery',path:'',offset:0,limit:100});
  assert.equal(invalid.status,400);
  const unauthorized = await access(base + '/v1/rx/request','wrong','POST',
    {op:'gallery',path:'',offset:0,limit:30});
  assert.equal(unauthorized.status,401);
  for (const [command,answer] of [
    [{op:'gallery',path:'',offset:0,limit:30},{ok:true,entries:[],nextOffset:0,hasMore:false}],
    [{op:'thumb',path:'DCIM/a.jpg',offset:0},{ok:true,base64:'ZmFrZQ=='}],
    [{op:'preview',path:'DCIM/a.jpg',offset:0},{ok:true,base64:'ZmFrZQ=='}],
  ]) {
    const nextPromise = access(base+'/v1/tx/next',txToken);
    await new Promise(resolve => setTimeout(resolve, 20));
    const request = await access(base+'/v1/rx/request',rxToken,'POST',
      { ...command, deviceId });
    assert.equal(request.status,202);
    const {id}=await request.json();
    const next=await nextPromise;
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


test('two phones receive only their own commands; cross-device result is rejected', async () =>
  withRelay(async base => {
    const next1 = access(base + '/v1/tx/next', txToken);
    const next2 = access(base + '/v1/tx/next', txToken2);
    await new Promise(resolve => setTimeout(resolve, 30));
    const devices = await access(base + '/v1/rx/devices', rxToken);
    assert.equal(devices.status, 200);
    const online = (await devices.json()).devices;
    assert.equal(online.length, 2);
    assert.equal(online.find(d => d.id === deviceId).online, true);
    assert.equal(online.find(d => d.id === device2Id).online, true);

    const rx1 = await access(base + '/v1/rx/request', rxToken, 'POST',
      { deviceId, op: 'list', path: 'DCIM/One', offset: 0, limit: 10 });
    const rx2 = await access(base + '/v1/rx/request', rxToken, 'POST',
      { deviceId: device2Id, op: 'list', path: 'DCIM/Two', offset: 0, limit: 10 });
    assert.equal(rx1.status, 202);
    assert.equal(rx2.status, 202);
    const id1 = (await rx1.json()).id;
    const id2 = (await rx2.json()).id;
    const cmd1 = await (await next1).json();
    const cmd2 = await (await next2).json();
    assert.deepEqual([cmd1.id, cmd1.path], [id1, 'DCIM/One']);
    assert.deepEqual([cmd2.id, cmd2.path], [id2, 'DCIM/Two']);

    const cross = await access(base + '/v1/tx/result/' + id1, txToken2, 'POST',
      { ok: true, entries: [] });
    assert.equal(cross.status, 404);
    const good1 = await access(base + '/v1/tx/result/' + id1, txToken, 'POST',
      { ok: true, entries: [{ name: 'one' }] });
    const good2 = await access(base + '/v1/tx/result/' + id2, txToken2, 'POST',
      { ok: true, entries: [{ name: 'two' }] });
    assert.equal(good1.status, 200);
    assert.equal(good2.status, 200);
    const out1 = await access(base + '/v1/rx/result/' + id1, rxToken);
    const out2 = await access(base + '/v1/rx/result/' + id2, rxToken);
    assert.equal((await out1.json()).entries[0].name, 'one');
    assert.equal((await out2.json()).entries[0].name, 'two');
    const oldGlobal = await access(base + '/v1/tx/next', rootTxToken);
    assert.equal(oldGlobal.status, 401);
  }));
