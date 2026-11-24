import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';

// Only external execution is simulated. Java still owns sessions, jobs, transitions and artifacts.
// token：与被测后端一致的 Agent 内部令牌；sandboxToken：沙箱管理令牌；controlToken：仅测试用例控制场景。
export async function startMocks({ port, backend, token, sandboxToken, controlToken, runRoot }) {
  let scenario = { agent: 'success', docker: 'pass' };
  let calls = [];
  let agentCalls = 0;
  let testCalls = 0;
  const leases = new Map();
  const active = new Map();
  const json = (res, value, status = 200) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(value)); };
  const callback = async (session, op, body) => {
    const response = await fetch(`${backend}/api/internal/agent/sessions/${session}/${op}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Internal-Token': token }, body: JSON.stringify(body),
    });
    const result = await response.json();
    if (result.code !== 0) throw new Error(`mock callback ${op}: ${result.message}`);
    return result.data;
  };
  const safePath = (root, relative) => {
    const file = path.resolve(root, relative);
    if (!file.startsWith(path.resolve(runRoot) + path.sep) || !file.startsWith(path.resolve(root) + path.sep)) throw new Error('Mock write escaped test workspace');
    return file;
  };
  const server = http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://127.0.0.1');
      let raw = ''; for await (const chunk of req) raw += chunk;
      const body = raw ? JSON.parse(raw) : {};
      if (url.pathname === '/health') return json(res, { status: 'UP', mode: 'mock' });
      if (url.pathname.startsWith('/__')) {
        if (req.headers['x-test-token'] !== controlToken) return json(res, { error: 'unauthorized' }, 401);
        if (url.pathname === '/__scenario') { scenario = { agent: 'success', docker: 'pass', ...body }; agentCalls = testCalls = 0; calls = []; }
        return json(res, { scenario, calls, leases: [...leases.keys()] });
      }
      if (url.pathname.startsWith('/api/v1/leases/')) {
        if (req.headers['x-sandbox-token'] !== sandboxToken) return json(res, {}, 401);
        const [, leaseId, op] = url.pathname.match(/leases\/(\d+)\/(.*)/) || [];
        calls.push({ type: 'sandbox', leaseId, op });
        if (op === 'attach') {
          if (scenario.docker === 'attach-error') return json(res, { message: 'simulated Docker unavailable' }, 503);
          if (!path.resolve(body.worktreeHostPath).startsWith(path.resolve(runRoot) + path.sep)) throw new Error('Not a test repository');
          leases.set(leaseId, body);
          return json(res, { status: 'ATTACHED', codeToolsBaseUrl: `http://127.0.0.1:${port}` });
        }
        if (op === 'detach') { leases.delete(leaseId); return json(res, { status: 'CLOSED' }); }
        const lease = leases.get(leaseId); if (!lease) return json(res, { message: 'unknown mock lease' }, 404);
        if (op === 'code/write') {
          const file = safePath(lease.worktreeHostPath, body.relativePath);
          await fs.mkdir(path.dirname(file), { recursive: true }); await fs.writeFile(file, body.content || '');
          return json(res, { ok: true, data: { relativePath: body.relativePath } });
        }
        if (op === 'code/action') {
          testCalls++;
          const failed = scenario.docker === 'fail' || (scenario.docker === 'fail-once' && testCalls === 1);
          return json(res, { exitCode: failed ? 1 : 0, stdout: failed ? 'MOCK TEST FAILURE: expected fixed=true' : 'MOCK: 3 tests, 0 failures', stderr: '', repoPath: lease.worktreeHostPath, action: body.action });
        }
        return json(res, { ok: true });
      }
      if (url.pathname === '/v1/chat/stop') { active.get(body.run_id)?.(); return json(res, { ok: true, cancelled: true }); }
      if (url.pathname === '/v1/chat/title') return json(res, { title: 'Mock test conversation' });
      if (url.pathname === '/v1/chat/stream') {
        if (req.headers['x-internal-token'] !== token) return json(res, {}, 401);
        agentCalls++;
        const mode = scenario.agent;
        calls.push({ type: 'agent', sessionId: body.session_id, task: body.file_index?.taskTypeCode, runId: body.run_id });
        if (mode === 'transport-error' || (mode === 'transport-error-once' && agentCalls === 1)) return json(res, { message: 'simulated upstream failure' }, 503);
        res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
        const event = (type, data) => { if (!res.destroyed) res.write(`event: ${type}\ndata: ${JSON.stringify(data)}\n\n`); };
        active.set(body.run_id, () => { event('done', { cancelled: true }); res.end(); });
        res.on('close', () => active.delete(body.run_id));
        event('step', { step: 'mock_execution', message: 'Deterministic external execution', llmRound: 1 });
        if (mode === 'slow') { event('content', { delta: 'Mock waiting for stop' }); return; }
        if (mode === 'confirm' || (mode === 'confirm-once' && agentCalls === 1)) {
          event('content', { delta: JSON.stringify({ phase: 'need_human_confirm', findings: [{ id: 'F1', title: 'Confirm repair scope', suggestions: ['Apply minimal fix'], recommend: 'Apply minimal fix' }], openQuestions: [{ id: 'Q1', question: 'Expected behavior?' }] }) });
        } else if (mode === 'malformed') {
          event('content', { delta: '{"phase":"need_human_confirm", broken JSON' });
        } else {
          const task = body.file_index?.taskTypeCode;
          const allText = (body.messages || []).map(m => typeof m.content === 'string' ? m.content : '').join('\n');
          const names = { GEN_ISSUE_ANALYSIS: '问题原因分析.md', GEN_ISSUE_FIX: '问题修复报告.md', GEN_INT_TEST: '集成测试报告.md', GEN_PRD: '产品需求说明书.md', REVIEW_PRE: '原始需求-业务评估.md', REVIEW_PRE_TECH: '原始需求-技术评估.md' };
          const name = body.file_index?.stageDeliverableName || names[task] || allText.match(/输出文件名=([^\s\n；。]+)/)?.[1];
          const fix = task === 'GEN_ISSUE_FIX' || body.file_index?.codingStage === 'INT_TEST_FIX';
          if (fix && mode !== 'no-diff') await callback(body.session_id, 'code/write', { repoIndex: 0, relativePath: 'src/mock-fix.txt', content: `fixed by mock run ${body.run_id}\n` });
          let content = '# 自动化测试模拟产物\n\n这是固定执行结果，非真实模型质量证明。\n\n';
          content += task === 'GEN_ISSUE_FIX' ? '**结论：已修复**' : task === 'GEN_INT_TEST' ? '**结论：通过**' : '## 分析结论\n问题已定位。\n建议做，可做。';
          if (name && mode !== 'missing-output' && body.file_index?.codingStage !== 'INT_TEST_FIX') {
            await callback(body.session_id, 'write', { displayName: name, content });
          }
          event('content', { delta: mode === 'missing-output' ? 'done' : content });
        }
        event('usage', { prompt_tokens: 100, completion_tokens: 20, total_tokens: 120 });
        event('done', { run_id: body.run_id }); res.end(); return;
      }
      // Java code review / merge use an OpenAI-compatible endpoint independently of Python.
      if (url.pathname.endsWith('/chat/completions')) {
        calls.push({ type: 'review-model' });
        return json(res, { id: 'mock-review', choices: [{ index: 0, message: { role: 'assistant', content: JSON.stringify({ issues: [], findings: [], items: [], queries: [], summary: 'No simulated findings' }) }, finish_reason: 'stop' }], usage: { prompt_tokens: 10, completion_tokens: 10, total_tokens: 20 } });
      }
      json(res, { message: 'Unknown mock operation', path: url.pathname }, 404);
    } catch (e) {
      if (res.headersSent) { res.write(`event: error\ndata: ${JSON.stringify({ message: e.message })}\n\n`); res.end(); }
      else json(res, { message: e.message }, 500);
    }
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve); });
  return { close: () => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }) };
}
