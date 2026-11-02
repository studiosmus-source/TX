/** Test harness for future RX Android app. Never stores remote files on the relay. */
import { writeFile, mkdir } from 'node:fs/promises';
import { dirname } from 'node:path';

const [url, command, remotePath = '', outputPath] = process.argv.slice(2);
const token = process.env.RX_TOKEN;
if (!url || !url.startsWith('https://') || !token || token.length < 32 || !['list', 'download'].includes(command)) {
  console.error('Usage: RX_TOKEN=<secret> node rx-cli.mjs https://relay.example list [relative/dir]');
  console.error('   or: RX_TOKEN=<secret> node rx-cli.mjs https://relay.example download relative/file local/path');
  process.exit(2);
}
if (command === 'download' && (!remotePath || !outputPath)) process.exit(2);
const base = url.replace(/\/$/, '');

async function api(method, path, body) {
  const response = await fetch(base + path, {
    method,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(35_000),
  });
  const json = await response.json();
  if (response.status !== 202 && !response.ok) throw new Error(`HTTP ${response.status}: ${JSON.stringify(json)}`);
  return json;
}

async function call(command) {
  const queued = await api('POST', '/v1/rx/request', command);
  for (let n = 0; n < 5; n++) {
    const answer = await api('GET', '/v1/rx/result/' + queued.id);
    if (answer.pending) continue;
    if (!answer.ok) throw new Error(answer.error || 'TX error');
    return answer;
  }
  throw new Error('No response from transmitter within ~2 minutes');
}

if (command === 'list') {
  let offset = 0;
  while (true) {
    const result = await call({ op: 'list', path: remotePath, offset, limit: 100 });
    for (const entry of result.entries) console.log(`${entry.directory ? '[DIR]' : '[FILE]'} ${entry.hidden ? '(hidden)' : ''} ${entry.path}`);
    offset += result.entries.length;
    if (!result.hasMore) break;
  }
} else {
  let offset = 0;
  const buffers = [];
  let total = -1;
  while (true) {
    const result = await call({ op: 'read', path: remotePath, offset, length: 65536 });
    buffers.push(Buffer.from(result.base64, 'base64'));
    offset = result.nextOffset;
    total = result.totalBytes;
    if (result.complete) break;
  }
  if (offset !== total) throw new Error('Incomplete file');
  await mkdir(dirname(outputPath), { recursive: true });
  await writeFile(outputPath, Buffer.concat(buffers));
  console.log(`Downloaded ${offset} bytes to ${outputPath}`);
}
