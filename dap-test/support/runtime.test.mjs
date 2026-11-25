import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { createRuntimeDirectory, ensureTestRepository, cleanupRuntimeDirectory } from './runtime.mjs';

test('plain fixtures have no Git repo; workflow initialization is lazy and idempotent', async () => {
  const root = await createRuntimeDirectory(), repo = path.join(root, 'repository');
  try {
    await fs.mkdir(repo);
    await assert.rejects(fs.access(path.join(repo, '.git')));
    await Promise.all([ensureTestRepository(repo), ensureTestRepository(repo)]);
    assert.equal(execFileSync('git', ['rev-list', '--count', 'HEAD'], { cwd: repo, encoding: 'utf8', windowsHide: true }).trim(), '1');
    await ensureTestRepository(repo);
    assert.equal(execFileSync('git', ['rev-list', '--count', 'HEAD'], { cwd: repo, encoding: 'utf8', windowsHide: true }).trim(), '1');
  } finally { await cleanupRuntimeDirectory(root); }
  await assert.rejects(fs.access(root));
});

test('runtime is kept only on explicit request and cleanup covers generated worktrees', async () => {
  const root = await createRuntimeDirectory();
  await fs.mkdir(path.join(root, 'repository-worktrees', 'fix-test'), { recursive: true });
  try {
    assert.equal(await cleanupRuntimeDirectory(root, true), false);
    await fs.access(root);
  } finally { await cleanupRuntimeDirectory(root); }
  await assert.rejects(fs.access(root));
});

test('cleanup refuses a source workspace or unmanaged path', async () => {
  await assert.rejects(cleanupRuntimeDirectory(process.cwd()), /unmanaged/);
});
