import { test, expect, unique } from '../../support/fixtures.js';

test.beforeEach(async ({ api }) => { await api.scenario({}); });
test('normal chat streams content and persists both messages', async ({ api, f }) => {
  const session = await api.post(`/workbench/workspaces/${f.workspaceId}/sessions`, { sessionType: 'NORMAL', title: unique('Chat') });
  const response = await fetch(`${f.backend}/api/chat/completions`, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${f.users.owner.token}`, 'X-Tenant-Id': String(f.tenantId) }, body: JSON.stringify({ sessionId: session.id, content: 'Test streaming' }) });
  const text = await response.text(); expect(text).toMatch(/event:\s*content/); expect(text).toMatch(/event:\s*done/);
  const messages = await api.get(`/chat/sessions/${session.id}/messages`);
  expect(messages.some((m: any) => m.role === 'user' && m.content === 'Test streaming')).toBeTruthy();
  expect(messages.some((m: any) => m.role === 'assistant' && m.status === 'done' && m.content.length > 0)).toBeTruthy();
});
test('another user cannot read private chat messages', async ({ api, f }) => {
  const session = await api.post(`/workbench/workspaces/${f.workspaceId}/sessions`, { sessionType: 'NORMAL', title: unique('PrivateChat') });
  await api.as('operator').denied('GET', `/chat/sessions/${session.id}/messages`);
});
test('closed session is no longer accessible after deletion', async ({ api, f }) => {
  const prefix = `/workbench/workspaces/${f.workspaceId}`;
  const session = await api.post(`${prefix}/sessions`, { sessionType: 'NORMAL', title: unique('DeleteChat') });
  await api.delete(`${prefix}/sessions/${session.id}`); await api.denied('GET', `/chat/sessions/${session.id}/messages`);
});
test('transport failure retries the real job before producing output', async ({ api }) => {
  await api.scenario({ agent: 'transport-error-once' });
  const issue = await api.issue(); const instance = await api.pipeline(issue.id);
  const unit = await api.waitUnit(instance, 'GEN_ISSUE_ANALYSIS', ['WAITING_TRIGGER']);
  await api.post(`/pipeline/units/${unit.id}/trigger`);
  const result = await api.waitUnit(instance, 'GEN_ISSUE_ANALYSIS', ['WAITING_PUBLISH'], 45000);
  expect(result.outputWorkspaceNodeId).toBeTruthy();
  expect((await api.mockState()).calls.filter((c: any) => c.type === 'agent').length).toBeGreaterThanOrEqual(2);
});
