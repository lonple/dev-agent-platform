import { test, expect } from '../../support/fixtures.js';

test.beforeEach(async ({ api }) => { await api.scenario({}); });

async function start(api: any) {
  const r = await api.requirement();
  const { id } = await api.post('/pipeline/instances', { requirementId: r.id });
  api.instances.push(id);
  return { requirement: r, id };
}
async function preReviews(api: any, id: number) {
  for (const task of ['REVIEW_PRE', 'REVIEW_PRE_TECH']) {
    const unit = await api.waitUnit(id, task, ['WAITING_TRIGGER', 'RUNNING', 'WAITING_PUBLISH']);
    if (unit.status === 'WAITING_TRIGGER') await api.post(`/pipeline/units/${unit.id}/trigger`);
    const result = await api.waitUnit(id, task, ['WAITING_PUBLISH']);
    await api.post(`/pipeline/units/${result.id}/publish`);
    await api.waitUnit(id, task, ['SUCCEEDED']);
  }
}

test('requirement cannot bypass AI pre-review and start development', async ({ api, f }) => {
  const { id } = await start(api);
  await api.denied('POST', `/pipeline/instances/${id}/pm-second`, { action: 'PASS_AND_DEV', releaseVersionId: f.versionId });
  expect((await api.get(`/pipeline/instances/${id}`)).nodes.find((n: any) => n.nodeCode === 'gen_prd').status).toBe('PENDING');
});

test('requirement pipeline rejects duplicate start and foreign tenant access', async ({ api, f }) => {
  const { requirement, id } = await start(api);
  await api.denied('POST', '/pipeline/instances', { requirementId: requirement.id });
  await api.as('other', f.otherTenantId).denied('GET', `/pipeline/instances/${id}`);
});

test('both pre-reviews publish before PM may suspend then resume into PRD', async ({ api, f }) => {
  const { id } = await start(api); await preReviews(api, id);
  const decision = { action: 'PASS', releaseVersionId: f.versionId, enableArch: false, enableUi: false };
  await api.post(`/pipeline/instances/${id}/pm-second`, decision);
  expect((await api.get(`/pipeline/instances/${id}`)).status).toBe('SUSPENDED');
  await api.post(`/pipeline/instances/${id}/pm-second`, { ...decision, action: 'PASS_AND_DEV' });
  const prd = await api.waitUnit(id, 'GEN_PRD', ['WAITING_PUBLISH']);
  expect(prd.outputWorkspaceNodeId).toBeTruthy();
  await api.post(`/pipeline/units/${prd.id}/publish`);
  await api.waitUnit(id, 'GEN_PRD', ['SUCCEEDED']);
  const instance = await api.get(`/pipeline/instances/${id}`);
  expect(instance.nodes.find((n: any) => n.nodeCode === 'gen_arch').enabledFlag).toBe(false);
  expect(instance.nodes.find((n: any) => n.nodeCode === 'gen_ui').enabledFlag).toBe(false);
  await api.denied('POST', `/pipeline/instances/${id}/pm-second`, { ...decision, action: 'PASS_AND_DEV' });
});
