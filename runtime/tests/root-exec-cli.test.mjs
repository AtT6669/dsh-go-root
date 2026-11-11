// The guest CLI is the only consumer of the root bridge inside the runtime. These tests run it
// against a stand-in HTTP server on loopback, so they cover the contract without a phone:
// exit codes (2/3/125/command), stdout passthrough and the bearer token.
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const cli = fileURLToPath(new URL('../root-exec/dsh-root.mjs', import.meta.url));
const TOKEN = 'test-token-0123456789abcdef';

function baseEnv() {
  const env = { ...process.env };
  delete env.DSH_ROOT_EXEC_URL;
  delete env.DSH_ROOT_EXEC_TOKEN;
  delete env.DSH_ROOT_EXEC_VERSION;
  return env;
}

// handler({ method, path, headers, body }) -> { status, json }
async function bridge(t, handler) {
  const seen = [];
  const server = http.createServer((req, res) => {
    let text = '';
    req.setEncoding('utf8');
    req.on('data', (chunk) => { text += chunk; });
    req.on('end', () => {
      const request = {
        method: req.method,
        path: req.url,
        headers: req.headers,
        body: text ? JSON.parse(text) : null,
      };
      seen.push(request);
      if (req.headers.authorization !== `Bearer ${TOKEN}`) {
        res.writeHead(403, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ ok: false, error: 'unauthorized' }));
        return;
      }
      const reply = handler(request);
      res.writeHead(reply.status, { 'content-type': 'application/json' });
      res.end(JSON.stringify(reply.json));
    });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));
  return { url: `http://127.0.0.1:${server.address().port}`, seen };
}

function run(args, env = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [cli, ...args], {
      env: { ...baseEnv(), ...env },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let stdout = '';
    let stderr = '';
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.stderr.on('data', (chunk) => { stderr += chunk; });
    const timer = setTimeout(() => {
      child.kill('SIGKILL');
      reject(new Error(`dsh-root stalled: ${stdout}${stderr}`));
    }, 20_000);
    child.on('error', (error) => { clearTimeout(timer); reject(error); });
    child.on('close', (code) => { clearTimeout(timer); resolve({ code, stdout, stderr }); });
  });
}

test('without DSH_ROOT_EXEC_URL/TOKEN the CLI exits 2 and points at the app', async () => {
  const result = await run(['id', '-u']);
  assert.equal(result.code, 2);
  assert.match(result.stderr, /DSH_ROOT_EXEC_URL/);
  assert.match(result.stderr, /Root 增强/);
  assert.equal(result.stdout, '');
});

test('--status exits 3 while the app has not authorized', async (t) => {
  const server = await bridge(t, () => ({ status: 200, json: { ok: true, v: 1, authorized: false } }));
  const result = await run(['--status'], { DSH_ROOT_EXEC_URL: server.url, DSH_ROOT_EXEC_TOKEN: TOKEN });
  assert.equal(result.code, 3);
  assert.match(result.stdout, /root 通道: 可用/);
  assert.match(result.stdout, /已授权: 否/);
  assert.equal(server.seen.length, 1);
  assert.equal(server.seen[0].method, 'GET');
  assert.equal(server.seen[0].path, '/v1/ping');
});

test('--status exits 0 once authorized', async (t) => {
  const server = await bridge(t, () => ({ status: 200, json: { ok: true, v: 1, authorized: true } }));
  const result = await run(['--status'], { DSH_ROOT_EXEC_URL: server.url, DSH_ROOT_EXEC_TOKEN: TOKEN });
  assert.equal(result.code, 0);
  assert.match(result.stdout, /已授权: 是/);
});

test('exec passes stdout through and exits with the remote code', async (t) => {
  const server = await bridge(t, () => ({
    status: 200,
    json: { ok: true, exitCode: 7, stdout: 'hello root\n', stderr: '', truncated: false, durationMs: 5 },
  }));
  const result = await run(['id', '-u'], { DSH_ROOT_EXEC_URL: server.url, DSH_ROOT_EXEC_TOKEN: TOKEN });
  assert.equal(server.seen.length, 1);
  assert.equal(server.seen[0].method, 'POST');
  assert.equal(server.seen[0].path, '/v1/exec');
  assert.equal(server.seen[0].body.command, 'id -u');
  assert.equal(server.seen[0].headers.authorization, `Bearer ${TOKEN}`);
  assert.equal(result.code, 7);
  assert.equal(result.stdout, 'hello root\n');
  assert.equal(result.stderr, '');
});

test('a wrong token is refused as unauthorized (exit 125)', async (t) => {
  const server = await bridge(t, () => ({ status: 200, json: { ok: true } }));
  const result = await run(['id', '-u'], { DSH_ROOT_EXEC_URL: server.url, DSH_ROOT_EXEC_TOKEN: 'wrong-token' });
  assert.equal(result.code, 125);
  assert.match(result.stderr, /未授权/);
  assert.equal(server.seen.length, 1);
});

test('--help prints usage and exits 0 without touching the bridge', async () => {
  const result = await run(['--help']);
  assert.equal(result.code, 0);
  assert.match(result.stdout, /^dsh-root —/);
  assert.match(result.stdout, /--status/);
});
