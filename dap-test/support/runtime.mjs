import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const exec = promisify(execFile);
const marker = '.dap-test-runtime';
const initializations = new Map();

export async function createRuntimeDirectory() {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'dap-test-'));
  await fs.writeFile(path.join(root, marker), 'dap-test-runtime-v1');
  return root;
}

async function validateRuntime(root) {
  const resolved = path.resolve(root);
  if (path.dirname(resolved) !== path.resolve(os.tmpdir()) || !path.basename(resolved).startsWith('dap-test-')) {
    throw new Error('Refusing to access an unmanaged test runtime directory');
  }
  const stat = await fs.lstat(resolved);
  if (!stat.isDirectory() || stat.isSymbolicLink() || await fs.readFile(path.join(resolved, marker), 'utf8') !== 'dap-test-runtime-v1') {
    throw new Error('Test runtime ownership marker is missing');
  }
  return resolved;
}

/** 只有真正调用仓库工作流时才创建 Git 基线；普通接口/页面只需要一个目录。 */
export async function ensureTestRepository(repoPath) {
  const root = await validateRuntime(path.dirname(repoPath));
  if (path.resolve(repoPath) !== path.join(root, 'repository')) throw new Error('Unexpected fixture repository path');
  if (initializations.has(root)) return initializations.get(root);
  const promise = (async () => {
    const ready = path.join(root, '.repository-ready');
    try { await fs.access(ready); return; } catch { /* 当前运行尚未初始化。 */ }
    await fs.mkdir(path.join(repoPath, 'src'), { recursive: true });
    await fs.writeFile(path.join(repoPath, 'README.md'), '# Deterministic test repository\n');
    await fs.writeFile(path.join(repoPath, 'src/example.txt'), 'original\n');
    for (const args of [['init', '-b', 'main'], ['config', 'user.name', 'DAP Test'], ['config', 'user.email', 'dap-test@example.invalid'], ['add', '.'], ['commit', '-m', 'test fixture baseline']]) {
      await exec('git', args, { cwd: repoPath, windowsHide: true });
    }
    await fs.writeFile(ready, 'ready');
  })();
  initializations.set(root, promise);
  try { await promise; } catch (error) { initializations.delete(root); throw error; }
}

export async function cleanupRuntimeDirectory(root, keep = false) {
  const resolved = await validateRuntime(root);
  if (keep) return false;
  await fs.rm(resolved, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 });
  initializations.delete(resolved);
  return true;
}
