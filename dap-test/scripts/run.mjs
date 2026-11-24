// dotenv 仅用于加载 .env，属于可选依赖：未安装（或 .env 缺失）时不得阻断用例执行，
// 环境变量也可由运行方案 / Node 内置 --env-file 注入，因此此处做静默降级。
try {
  const { config } = await import('dotenv');
  config?.();
} catch {
  // 依赖缺失或加载失败：忽略，继续执行，避免 ERR_MODULE_NOT_FOUND 掩盖真实用例结果。
}

import fsp from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import { startMocks } from '../mocks/server.mjs';
import { provision } from './setup.mjs';

// 对运行中的 DAP 开发环境执行测试。数据库、构建、服务启动均由运行方案负责，本脚本不创建或删除数据库。
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repo = path.dirname(root);
const id = `${Date.now().toString(36)}${crypto.randomBytes(2).toString('hex')}`;
const runRoot = path.join(root, '.runtime', id);
const trim = value => (value || '').replace(/\/+$/, '');
const backend = trim(process.env.DAP_TEST_API_URL);
const ui = trim(process.env.DAP_TEST_UI_URL);
const modelMode = (process.env.DAP_TEST_MODEL_MODE || 'real').toLowerCase();
const mockPort = Number(process.env.DAP_TEST_MOCK_PORT || 18202);
const controlToken = crypto.randomBytes(24).toString('hex');
let mocks;
let test;
let exitCode = 1;
let failure;

async function reachable(url, label) {
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(5000) });
    if (response.status < 500) return;
    throw new Error(`HTTP ${response.status}`);
  } catch (e) {
    throw new Error(`${label} is not reachable at ${url} (${e.message}). Start the DAP environment from its runtime profile first.`);
  }
}

process.on('SIGINT', () => { exitCode = 130; test?.kill('SIGINT'); });
process.on('SIGTERM', () => { exitCode = 130; test?.kill('SIGTERM'); });
try {
  if (!backend) throw new Error('DAP_TEST_API_URL is required (generated from the runtime profile test settings).');
  if (!ui) throw new Error('DAP_TEST_UI_URL is required (generated from the runtime profile test settings).');
  if (!['real', 'mock'].includes(modelMode)) throw new Error('DAP_TEST_MODEL_MODE must be real or mock');
  await fsp.mkdir(runRoot, { recursive: true });
  if (modelMode === 'mock') {
    // 被测后端已由运行方案把 Agent、审查模型和沙箱管理地址指向此端口；令牌与后端保持一致。
    mocks = await startMocks({
      port: mockPort, backend, runRoot, controlToken,
      token: process.env.DAP_AGENT_TOKEN || 'dev-internal-token',
      sandboxToken: process.env.DAP_SANDBOX_SHAREDTOKEN || 'dev-sandbox-token',
    });
  }
  await reachable(`${backend}/api/auth/me`, 'DAP backend');
  await reachable(`${ui}/login`, 'DAP frontend');
  console.log(`[dap-test] Target ${backend} / ${ui}; model mode: ${modelMode}. Preparing run-scoped fixtures...`);
  const fixtures = await provision({
    backend, runRoot, id,
    mock: modelMode === 'mock' ? `http://127.0.0.1:${mockPort}` : null, token: controlToken,
    username: process.env.DAP_TEST_USERNAME || 'admin', password: process.env.DAP_TEST_PASSWORD || 'admin',
  });
  const stateFile = path.join(runRoot, 'fixtures.json');
  await fsp.writeFile(stateFile, JSON.stringify(fixtures));
  const args = process.argv.slice(2);
  const cli = path.join(root, 'node_modules', '@playwright', 'test', 'cli.js');
  try {
    await fsp.access(cli);
  } catch {
    throw new Error('Playwright is not installed for dap-test. Run "npm --prefix dap-test install" first.');
  }
  console.log('[dap-test] Running Playwright suites...');
  test = spawn(process.execPath, [cli, 'test', ...args], {
    cwd: root,
    stdio: 'inherit',
    env: { ...process.env, DAP_TEST_FIXTURES: stateFile },
  });
  exitCode = await new Promise(resolve => {
    test.on('close', code => resolve(typeof code === 'number' ? code : 1));
    test.on('error', e => { console.error(`[dap-test] Failed to start Playwright: ${e.message}`); resolve(1); });
  });
} catch (e) {
  failure = e;
  console.error(`[dap-test] ${e?.message || e}`);
} finally {
  try { await mocks?.close?.(); } catch { /* 关闭 mock 失败不覆盖用例结果 */ }
  if (!failure && exitCode === 0) {
    await fsp.rm(runRoot, { recursive: true, force: true }).catch(() => {});
  } else if (!failure) {
    console.log(`[dap-test] Artifacts kept in ${runRoot}`);
  }
}
process.exitCode = failure ? 1 : exitCode;
// 仓库根仅供调试信息输出，避免未使用告警。
if (failure) console.error(`[dap-test] repo: ${repo}`);
