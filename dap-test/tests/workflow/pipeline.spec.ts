import { test, expect } from '../../support/fixtures.js';

const analysis = 'GEN_ISSUE_ANALYSIS';
const fix = 'GEN_ISSUE_FIX';
async function start(api: any) {
  const issue = await api.issue(); const instance = await api.pipeline(issue.id);
  const unit = await api.waitUnit(instance, analysis, ['WAITING_TRIGGER']);
  return { issue, instance, unit };
}
async function trigger(api: any, instance: number, unit: any, statuses = ['WAITING_PUBLISH']) {
  await api.post(`/pipeline/units/${unit.id}/trigger`); return api.waitUnit(instance, unit.taskTypeCode, statuses);
}
async function confirm(api: any, unit: any) {
  return api.post(`/pipeline/units/${unit.id}/confirm`, { findingAnswers: [{ id: 'F1', agree: true, comment: 'Approved test fixture scope' }], questionAnswers: [{ id: 'Q1', answer: 'Expected behavior must be preserved' }] });
}
test.beforeEach(async ({ api }) => { await api.scenario({}); });

test('duplicate active pipeline is rejected and cancellation persists', async ({ api }) => {
  const { issue, instance } = await start(api);
  await api.denied('POST', '/pipeline/instances', { issueId: issue.id });
  await api.post(`/pipeline/instances/${instance}/cancel`);
  expect((await api.get(`/pipeline/instances/${instance}`)).status).toBe('CANCELLED');
});
test('pipeline cannot reference another tenants issue', async ({ api, f }) => {
  const issue = await api.issue(); await api.as('other', f.otherTenantId).denied('POST', '/pipeline/instances', { issueId: issue.id });
});
test('mock Agent output creates a real workspace artifact and publishes to real Wiki', async ({ api }) => {
  const { instance, unit } = await start(api); const result = await trigger(api, instance, unit);
  expect(result.outputWorkspaceNodeId).toBeTruthy();
  await api.post(`/pipeline/units/${result.id}/publish`);
  const published = await api.waitUnit(instance, analysis, ['SUCCEEDED']); expect(published.outputWikiNodeId).toBeTruthy();
  const content = await api.get(`/workbench/wiki/nodes/${published.outputWikiNodeId}/content`); expect(content.content).toContain('自动化测试模拟产物');
});
test('human confirmation creates a new execution and then an output', async ({ api }) => {
  await api.scenario({ agent: 'confirm-once' });
  const { instance, unit } = await start(api); const waiting = await trigger(api, instance, unit, ['WAITING_CONFIRM']);
  const round = await api.get(`/pipeline/units/${waiting.id}/confirm`); expect(round.roundNo).toBe(1);
  await confirm(api, waiting); const output = await api.waitUnit(instance, analysis, ['WAITING_PUBLISH']); expect(output.outputWorkspaceNodeId).toBeTruthy();
  const state = await api.mockState(); expect(state.calls.filter((c: any) => c.type === 'agent')).toHaveLength(2);
});
test('confirmation cannot be submitted before the unit is waiting', async ({ api }) => {
  const { unit } = await start(api); await api.denied('POST', `/pipeline/units/${unit.id}/confirm`, { findingAnswers: [], questionAnswers: [] });
});
test('streaming unit can be stopped without publishing', async ({ api }) => {
  await api.scenario({ agent: 'slow' }); const { instance, unit } = await start(api);
  await trigger(api, instance, unit, ['RUNNING']);
  await expect.poll(async () => (await api.mockState()).calls.filter((c: any) => c.type === 'agent').length).toBeGreaterThan(0);
  await api.post(`/pipeline/units/${unit.id}/stop`); const stopped = await api.waitUnit(instance, analysis, ['FAILED']); expect(stopped.outputWikiNodeId).toBeFalsy();
});
test('invalid Agent confirmation response is not accepted as a formal deliverable', async ({ api }) => {
  await api.scenario({ agent: 'malformed' }); const { instance, unit } = await start(api);
  const result = await trigger(api, instance, unit, ['FAILED']); expect(result.outputWikiNodeId).toBeFalsy();
});
test('sandbox attach and release are real lease transitions against a mock manager', async ({ api, f }) => {
  const lease = await api.post('/work/sandbox/leases', { productId: f.productId, reqNo: 'MOCK-LEASE', worktreeHostPath: f.repoPath, purpose: 'PIPE_CODE' });
  expect(lease.status).toBe('ATTACHED'); await api.delete(`/work/sandbox/leases/${lease.leaseId}`);
  expect((await api.get(`/work/sandbox/leases?productId=${f.productId}`)).some((l: any) => l.id === lease.leaseId)).toBeFalsy();
  const state = await api.mockState(); expect(state.calls.some((c: any) => c.op === 'attach')).toBeTruthy(); expect(state.calls.some((c: any) => c.op === 'detach')).toBeTruthy();
});
test('simulated Docker failure must not leave an attached lease', async ({ api, f }) => {
  await api.scenario({ docker: 'attach-error' });
  await api.denied('POST', '/work/sandbox/leases', { productId: f.productId, reqNo: 'MOCK-FAIL', worktreeHostPath: f.repoPath });
  expect((await api.get(`/work/sandbox/leases?productId=${f.productId}`)).some((l: any) => l.reqNo === 'MOCK-FAIL')).toBeFalsy();
});
for (const failureFirst of [false, true]) {
test(`complete issue workflow ${failureFirst ? 'repairs failed tests and retests' : 'passes tests'} with real Git worktree`, async ({ api }) => {
  if (failureFirst) await api.scenario({ docker: 'fail-once' });
  const { instance, unit } = await start(api); let current = await trigger(api, instance, unit);
  await api.post(`/pipeline/units/${current.id}/publish`);
  current = await api.waitUnit(instance, fix, ['WAITING_TRIGGER', 'WAITING_PUBLISH']);
  if (current.status === 'WAITING_TRIGGER') current = await trigger(api, instance, current);
  await api.post(`/pipeline/units/${current.id}/publish`);
  current = await api.waitUnit(instance, 'GEN_ISSUE_CODE_REVIEW', ['WAITING_TRIGGER', 'WAITING_CONFIRM']);
  if (current.status === 'WAITING_TRIGGER') current = await trigger(api, instance, current, ['WAITING_CONFIRM']);
  await api.post(`/pipeline/units/${current.id}/confirm`, { findingAnswers: [], questionAnswers: [] });
  current = await api.waitUnit(instance, 'GEN_ISSUE_CODE_REVIEW', ['WAITING_PUBLISH', 'SUCCEEDED']);
  if (current.status === 'WAITING_PUBLISH') await api.post(`/pipeline/units/${current.id}/publish`);
  current = await api.waitUnit(instance, 'GEN_ISSUE_INT_TEST', failureFirst ? ['WAITING_TRIGGER', 'WAITING_CONFIRM'] : ['WAITING_TRIGGER', 'WAITING_PUBLISH']);
  if (current.status === 'WAITING_TRIGGER') current = await trigger(api, instance, current, failureFirst ? ['WAITING_CONFIRM'] : ['WAITING_PUBLISH']);
  if (failureFirst) {
    const round = await api.get(`/pipeline/units/${current.id}/confirm`);
    const findings = JSON.parse(round.findingsJson);
    expect(findings.length).toBeGreaterThan(0);
    await api.post(`/pipeline/units/${current.id}/confirm`, { findingAnswers: findings.map((f: any) => ({ id: f.id, agree: true, comment: 'Apply the minimal repair' })), questionAnswers: [] });
    current = await api.waitUnit(instance, 'GEN_ISSUE_INT_TEST', ['WAITING_PUBLISH']);
  }
  await api.post(`/pipeline/units/${current.id}/publish`);
  await expect.poll(async () => (await api.get(`/pipeline/instances/${instance}`)).status, { timeout: 15000 }).toBe('DONE');
  const state = await api.mockState(); expect(state.calls.filter((c: any) => c.op === 'code/action').length).toBe(failureFirst ? 2 : 1);
});
}
