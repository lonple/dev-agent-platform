import 'dotenv/config';
import fsp from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn, spawnSync } from 'node:child_process';
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
  const stateFile = path.join(runRoot, 'fixtures.json'); await fsp.writeFile(stateFile, JSON.stringify(fixtures));
  const args = process.argv.slice(2);
  // 工作流用例依赖 Mock 场景（失败重试、人工确认等），使用真实模型时只跑接口和页面。
  if (modelMode === 'real' && !args.some(a => a.startsWith('--project'))) args.push('--project=api', '--project=ui');
  if (modelMode === 'real' && args.includes('--project=workflow')) throw new Error('Workflow tests require model mode "mock".');
  const cli = path.join(root, 'node_modules/@playwright/test/cli.js');
  test = spawn(process.execPath, [cli, 'test', ...args], { cwd: root, windowsHide: true, stdio: 'inherit', env: {
    ...process.env, DAP_TEST_FIXTURES: stateFile, DAP_TEST_API_URL: backend, DAP_TEST_UI_URL: ui,
  } });
  exitCode = await new Promise((resolve, reject) => { test.once('exit', code => resolve(code ?? 1)); test.once('error', reject); });
} catch (e) { failure = e.message; console.error(`[dap-test] ${e.message}`); }
finally {
  await fsp.mkdir(path.join(root, 'reports'), { recursive: true });
  await fsp.writeFile(path.join(root, 'reports/run.json'), JSON.stringify({
    run: id, commit: spawnSync('git', ['rev-parse', 'HEAD'], { cwd: repo, encoding: 'utf8' }).stdout?.trim(),
    target: { api: backend, ui }, modelMode, mocks: modelMode === 'mock' ? ['LLM/Agent', 'Docker execution'] : [],
    fixturePrefix: `t${id}`, runtime: runRoot, exitCode, failure,
  }, null, 2));
  if (mocks) await mocks.close();
  process.exitCode = exitCode;
}
