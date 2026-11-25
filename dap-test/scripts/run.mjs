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
import { createRuntimeDirectory, cleanupRuntimeDirectory } from '../support/runtime.mjs';

// 对运行中的 DAP 开发环境执行测试。数据库、构建、服务启动均由运行方案负责，本脚本不创建或删除数据库。
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repo = path.dirname(root);
const id = `${Date.now().toString(36)}${crypto.randomBytes(2).toString('hex')}`;
let runRoot;

// —— 配置解析 ——
// 取值优先级固定为：进程环境变量 > dap-test/.env > 仓库根 .env > 内置默认值。
// dotenv 未安装 / .env 缺失时用下方轻量解析兜底，避免“配置取不到”直接表现为“集成测试不通过”。
const ENV_DEFAULTS = {
  DAP_TEST_API_URL: 'http://localhost:8080',
  DAP_TEST_UI_URL: 'http://localhost:5173',
  DAP_TEST_MODEL_MODE: 'mock',
};
const STRICT = ['1', 'true', 'yes', 'on'].includes(String(process.env.DAP_TEST_STRICT || '').trim().toLowerCase());
const ENV_FILES = [path.join(root, '.env'), path.join(repo, '.env')];

// 轻量 .env 解析：支持空行/注释、export 前缀、成对引号、行尾注释；不覆盖已存在的进程环境变量。
function parseEnvFile(text) {
  const parsed = {};
  for (const rawLine of String(text).split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const matched = /^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/.exec(line);
    if (!matched) continue;
    let value = matched[2].trim();
    const quoted = (value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"));
    if (quoted && value.length >= 2) {
      value = value.slice(1, -1);
    } else {
      value = value.replace(/\s+#.*$/, '').trim();
    }
    parsed[matched[1]] = value;
  }
  return parsed;
}

async function loadEnvFile(file) {
  let text;
  try {
    text = await fsp.readFile(file, 'utf8');
  } catch {
    return null; // 文件不存在：交给下一级来源
  }
  let applied = 0;
  for (const [key, value] of Object.entries(parseEnvFile(text))) {
    if (process.env[key] === undefined) {
      process.env[key] = value;
      applied += 1;
    }
  }
  return { file: path.relative(repo, file), applied };
}

const envApplied = [];
for (const file of ENV_FILES) {
  const result = await loadEnvFile(file);
  if (result) envApplied.push(result);
}

const configNotes = [];
function pick(name) {
  const fromEnv = (process.env[name] || '').trim();
  if (fromEnv) return fromEnv;
  const fallback = ENV_DEFAULTS[name];
  if (fallback === undefined) return '';
  if (STRICT) {
    console.error(`[dap-test] 严格模式下缺少配置 ${name}。取值来源链：进程环境变量 > dap-test/.env > 仓库根 .env > 内置默认值；请把这些变量写进 dap-test/.env（或仓库根 .env），或去掉 DAP_TEST_STRICT 以使用默认值 ${fallback}。`);
    process.exit(1);
  }
  configNotes.push(`${name} 未从环境变量或 .env 取到，已回退默认值 ${fallback}`);
  return fallback;
}

const trim = value => (value || '').replace(/\/+$/, '');
const backend = trim(pick('DAP_TEST_API_URL'));
const ui = trim(pick('DAP_TEST_UI_URL'));
const modelMode = pick('DAP_TEST_MODEL_MODE').toLowerCase();
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
  const originText = envApplied.length
    ? envApplied.map(item => `${item.file}${item.applied ? `(补齐 ${item.applied} 项)` : '(无新增项)'}`).join(' > ')
    : '未找到 .env';
  console.log(`[dap-test] 配置来源：进程环境变量 > ${originText}；生效值 API=${backend} UI=${ui} MODEL_MODE=${modelMode}`);
  for (const note of configNotes) console.warn(`[dap-test] ${note}`);
  if (!backend) throw new Error('DAP_TEST_API_URL 为空：请写入 dap-test/.env（或仓库根 .env），或由运行方案注入进程环境变量。');
  if (!ui) throw new Error('DAP_TEST_UI_URL 为空：请写入 dap-test/.env（或仓库根 .env），或由运行方案注入进程环境变量。');
  if (!['real', 'mock'].includes(modelMode)) throw new Error('DAP_TEST_MODEL_MODE 仅支持 real 或 mock');
  runRoot = await createRuntimeDirectory();
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
  const keepRuntime = ['1', 'true'].includes(String(process.env.DAP_TEST_KEEP_RUNTIME || '').toLowerCase());
  try {
    const removed = runRoot ? await cleanupRuntimeDirectory(runRoot, keepRuntime) : true;
    if (!removed) console.log(`[dap-test] Runtime kept by DAP_TEST_KEEP_RUNTIME: ${runRoot}`);
  } catch (error) {
    console.warn(`[dap-test] Runtime cleanup failed at ${runRoot}: ${error.message}`);
  }
}
process.exitCode = failure ? 1 : exitCode;
// 仓库根仅供调试信息输出，避免未使用告警。
if (failure) console.error(`[dap-test] repo: ${repo}`);
