// Bundled plugins are installed at build time: runtime/locks/bundled-plugins.json is the only
// input the build reads, and rtbuild writes their row into the dsh-web overlay. These checks are
// offline: they read the lock and assemble the overlay in-memory.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const runtime = fileURLToPath(new URL('..', import.meta.url));
const lockPath = join(runtime, 'locks', 'bundled-plugins.json');
const rtbuildPath = join(runtime, 'lib', 'rtbuild.py');

const lock = JSON.parse(await readFile(lockPath, 'utf8'));
const byName = new Map(lock.plugins.map((plugin) => [plugin.name, plugin]));

function python() {
  for (const bin of [process.env.PYTHON, 'python3', 'python'].filter(Boolean)) {
    const probe = spawnSync(bin, ['-I', '-c', 'import sys; print(sys.version_info[0])'], { encoding: 'utf8' });
    if (probe.status === 0) return bin;
  }
  return null;
}

// The overlay is assembled by rtbuild itself, so the row under test is the row the build writes,
// not a copy of it in this file.
const PYTHON = python();

function overlay(withBundled) {
  const script = [
    'import importlib.util, json, sys',
    "spec = importlib.util.spec_from_file_location('rtbuild_probe', sys.argv[1])",
    'mod = importlib.util.module_from_spec(spec)',
    'sys.modules[spec.name] = mod',
    'spec.loader.exec_module(mod)',
    "entries = mod.overlay_entries({'runtimeVersion': 'test'}, with_pnpm=True, with_bridge=True,",
    '                            with_bundled=' + (withBundled ? 'True' : 'False') + ')',
    "want = ['usr/local/bin/dsh-root', 'opt/dsh-go/root-exec/dsh-root.mjs',",
    "        'root/.dsh/skills/dsh-root/SKILL.md']",
    'print(json.dumps({',
    "    'patch': entries['etc/dsh-app/dsh-web.patch.yml'].data.decode(),",
    "    'files': {p: {'kind': entries[p].kind, 'mode': entries[p].mode,",
    "                     'text': entries[p].read().decode() if entries[p].kind == 'file' else ''}",
    '              for p in want if p in entries},',
    "    'dirs': {p: entries[p].mode for p in ('root/.dsh/skills', 'root/.dsh/skills/dsh-root')",
    '             if p in entries},',
    '}))',
  ].join('\n');
  const run = spawnSync(PYTHON, ['-I', '-c', script, rtbuildPath], { encoding: 'utf8' });
  assert.equal(run.status, 0, `rtbuild overlay probe failed:\n${run.stderr}`);
  return JSON.parse(run.stdout);
}

test('lock has complete entries for dsh-purge and its yaml dependency', () => {
  assert.equal(lock.schema, 1);
  assert.ok(Array.isArray(lock.plugins) && lock.plugins.length >= 2);
  const purge = byName.get('dsh-purge');
  assert.ok(purge, 'dsh-purge is in the lock');
  assert.equal(purge.package, 'dsh-purge-community');
  assert.equal(purge.version, '1.6.91');
  assert.equal(purge.license, 'MIT');
  assert.equal(purge.sha256, 'ae22bb7d1ac1e055102c8fbd24cf2eedc282a94bfa3fb09e7ab91226fc033058');
  assert.match(purge.url, /^https:\/\/github\.com\/kingcharleslzy-ai\/dsh-purge\/releases\/download\/v1\.6\.91\//);
  const yaml = byName.get('yaml');
  assert.ok(yaml, 'yaml is in the lock');
  assert.equal(yaml.version, '2.9.1');
  assert.equal(yaml.sha256, '4ef6c54cf559b8a207b7b518378230805a1c84af239b14960e8c67c7d59de5d3');
  assert.equal(yaml.target, '/opt/dsh-go/plugins/node_modules/yaml');
});

test('lock fields, digests and targets stay usable by the build', () => {
  const targets = new Set();
  for (const plugin of lock.plugins) {
    for (const field of ['name', 'package', 'version', 'url', 'sha256', 'target', 'license']) {
      assert.equal(typeof plugin[field], 'string', `${plugin.name}.${field} is a string`);
      assert.ok(plugin[field].length > 0, `${plugin.name}.${field} is not empty`);
    }
    assert.match(plugin.sha256, /^[0-9a-f]{64}$/, `${plugin.name}.sha256 is a lowercase sha256`);
    assert.match(plugin.url, /^https:\/\/.+\.tgz$/, `${plugin.name}.url is a tarball URL`);
    assert.ok(plugin.url.includes(`${plugin.package}-${plugin.version}.tgz`),
      `${plugin.name}.url names ${plugin.package}-${plugin.version}.tgz`);
    assert.ok(plugin.target.startsWith('/opt/dsh-go/plugins/'),
      `${plugin.name}.target lives under /opt/dsh-go/plugins`);
    assert.equal(plugin.target, plugin.target.replace(/\/+$/, ''), `${plugin.name}.target has no trailing slash`);
    assert.ok(!targets.has(plugin.target), `${plugin.name}.target is unique`);
    targets.add(plugin.target);
  }
});

test('bundled targets stay clear of the bridge and the rest of dsh-app', () => {
  for (const plugin of lock.plugins) {
    assert.ok(!plugin.target.startsWith('/opt/dsh-go/dsh-go-bridge'), `${plugin.name} does not replace the bridge`);
    assert.ok(!plugin.target.startsWith('/opt/dsh-go/root-exec'), `${plugin.name} does not shadow root-exec`);
    assert.ok(!plugin.target.startsWith('/opt/dsh-go/direct'), `${plugin.name} does not shadow direct`);
    assert.ok(!plugin.target.includes('/../') && !plugin.target.includes('//'), `${plugin.name}.target is normalised`);
  }
  // yaml resolves through Node's node_modules walk from any bundled plugin's package dir.
  assert.ok(byName.get('yaml').target.startsWith('/opt/dsh-go/plugins/node_modules/'));
});

test('the overlay inserts dsh-purge once, and only its insert row', { skip: !PYTHON && 'python3 not found' }, () => {
  const withBundled = overlay(true);
  const patch = withBundled.patch;
  const expected = [
    '- insert:',
    '    - id: dsh-purge',
    '      name: /opt/dsh-go/plugins/dsh-purge-community/lib/index.js',
    '      inject: [systemPrompt, tools]',
    '      config: {enabled: true, excludePresets: []}',
    '',
  ].join('\n');
  assert.ok(patch.includes(expected), `overlay carries the dsh-purge insert row:\n${patch}`);
  assert.equal((patch.match(/id: dsh-purge/g) || []).length, 1, 'dsh-purge is inserted exactly once');
  // The bundle's own cordis.patch.yml also configures system-prompt and disables redteam-* ids;
  // those name plugins this runtime does not have and must not travel.
  assert.ok(!patch.includes('redteam-'), 'no redteam-* rows');
  assert.ok(!/^\s*system-prompt:/m.test(patch), 'no system-prompt row');
});

test('without the bundled flag the overlay is unchanged', { skip: !PYTHON && 'python3 not found' }, () => {
  const plain = overlay(false);
  assert.ok(!plain.patch.includes('dsh-purge'), 'no dsh-purge row when nothing was bundled');
  assert.ok(plain.patch.includes('id: dsh-go-bridge'), 'the bridge row is still there');
});

test('root-exec CLI, shim and skill are installed by the overlay', { skip: !PYTHON && 'python3 not found' }, () => {
  const { files, dirs } = overlay(true);
  const shim = files['usr/local/bin/dsh-root'];
  assert.ok(shim, 'usr/local/bin/dsh-root exists');
  assert.equal(shim.kind, 'file');
  assert.equal(shim.mode & 0o777, 0o755);
  assert.equal(shim.text, '#!/bin/sh\n'
    + '# dsh-app shim: run a command as root through the App\'s Root 增强 bridge.\n'
    + '# The URL and token arrive in the engine environment (DSH_ROOT_EXEC_URL/TOKEN).\n'
    + 'exec /opt/node/bin/node /opt/dsh-go/root-exec/dsh-root.mjs "$@"\n');
  const cli = files['opt/dsh-go/root-exec/dsh-root.mjs'];
  assert.ok(cli, 'the CLI is overlaid under /opt/dsh-go/root-exec');
  assert.equal(cli.mode & 0o777, 0o644);
  assert.match(cli.text, /^#!\/usr\/bin\/env node\n/, 'the CLI keeps its shebang');
  assert.ok(cli.text.includes('DSH_ROOT_EXEC_URL'), 'the CLI reads the bridge environment');
  const skill = files['root/.dsh/skills/dsh-root/SKILL.md'];
  assert.ok(skill, 'the skill lands in DSH home');
  assert.match(skill.text, /^---\nname: dsh-root\n/, 'the skill starts with front matter');
  assert.equal(dirs['root/.dsh/skills'] & 0o777, 0o755);
  assert.equal(dirs['root/.dsh/skills/dsh-root'] & 0o777, 0o755);
});
