import { test, expect, unique } from '../../support/fixtures.js';

test('login succeeds and current tenant is resolved', async ({ api, f }) => {
  const me = await api.get('/auth/me'); expect(me.user.username).toBe(f.users.owner.username); expect(me.currentTenantId).toBe(f.tenantId);
});
test('wrong password is rejected', async ({ api, f }) => { await api.as('none').denied('POST', '/auth/login', { username: f.users.owner.username, password: 'wrong' }); });
test('unauthenticated business access is rejected', async ({ api }) => { await api.as('none').denied('GET', '/products', undefined, [401]); });
test('operator cannot edit product configuration', async ({ api }) => { await api.as('operator').denied('POST', '/products', { code: 'UNAUTHORIZED', name: 'No' }, [403]); });
test('platform user without tenant membership cannot read tenant products', async ({ api }) => { await api.as('outsider').denied('GET', '/products', undefined, [400, 403]); });
test('product is created, updated and removed', async ({ api }) => {
  const p = await api.product(); const before = await api.get(`/products/${p.id}`);
  await api.put(`/products/${p.id}`, { code: before.code, name: 'Updated product', description: 'changed', codeRepos: [] });
  expect((await api.get(`/products/${p.id}`)).name).toBe('Updated product');
  await api.delete(`/products/${p.id}`); await api.denied('GET', `/products/${p.id}`, undefined, [404]);
});
test('duplicate product code is rejected', async ({ api }) => {
  const code = unique('DUP'); await api.product({ code }); await api.denied('POST', '/products', { code, name: unique('Other') });
});
test('repository purposes survive save and reload', async ({ api, f }) => {
  const p = await api.product({ codeRepos: [{ localPath: f.repoPath, repoKind: 'TEST', label: 'acceptance' }] });
  const saved = await api.get(`/products/${p.id}`); expect(saved.codeRepos[0]).toMatchObject({ repoKind: 'TEST', label: 'acceptance' });
  await api.put(`/products/${p.id}`, { code: saved.code, name: saved.name, codeRepos: saved.codeRepos });
  expect((await api.get(`/products/${p.id}`)).codeRepos[0].repoKind).toBe('TEST');
});
test('invalid repository purpose is rejected', async ({ api, f }) => { await api.denied('POST', '/products', { code: unique('BAD'), name: unique('Bad'), codeRepos: [{ localPath: f.repoPath, repoKind: 'INVALID' }] }); });
test('cross-tenant product reads and writes are rejected', async ({ api, f }) => {
  await api.denied('GET', `/products/${f.otherProductId}`, undefined, [404]);
  await api.denied('PUT', `/products/${f.otherProductId}`, { code: 'OTHERAPP', name: 'hijack' }, [404]);
});
test('planned product version requires a release date', async ({ api, f }) => { await api.denied('POST', `/products/${f.productId}/versions`, { version: unique('v'), status: 'PLANNING' }); });
test('version can be created and listed', async ({ api, f }) => {
  const version = unique('v'); await api.post(`/products/${f.productId}/versions`, { version, status: 'PLANNING', planReleaseAt: '2030-02-01T00:00:00' });
  expect((await api.get(`/products/${f.productId}/versions`)).some((v: any) => v.version === version)).toBeTruthy();
});
test('builtin role initialization is idempotent', async ({ api }) => {
  const first = await api.post('/positions/ensure-builtin'); const second = await api.post('/positions/ensure-builtin');
  expect(second.map((p: any) => p.id)).toEqual(first.map((p: any) => p.id)); expect(first.filter((p: any) => p.builtin)).toHaveLength(5);
});
test('builtin role code cannot be edited or deleted', async ({ api }) => {
  const role = (await api.get('/positions?withSkills=false')).find((p: any) => p.code === 'DEV');
  await api.denied('PUT', `/positions/${role.id}`, { name: role.name, code: 'RENAMED' }); await api.denied('DELETE', `/positions/${role.id}`);
  expect((await api.get(`/positions/${role.id}`)).code).toBe('DEV');
});
test('prompt content edits survive initialization', async ({ api }) => {
  const prompt = (await api.get('/prompts')).find((p: any) => p.taskTypeCode === 'GEN_ISSUE_ANALYSIS');
  const content = '# Customized tenant instructions'; await api.put(`/prompts/${prompt.id}`, { ...prompt, content });
  const result = await api.post('/prompts/ensure-pre-review'); expect(result.created).toHaveLength(0);
  expect((await api.get(`/prompts/${prompt.id}`)).content).toBe(content);
});
test('duplicate position-task prompt binding is rejected', async ({ api }) => {
  const prompt = (await api.get('/prompts'))[0]; await api.denied('POST', '/prompts', { ...prompt, code: unique('PROMPT') });
});
test('cross-tenant prompts are not visible', async ({ api, f }) => {
  await api.as('other', f.otherTenantId).post('/prompts/ensure-pre-review');
  const foreign = (await api.as('other', f.otherTenantId).get('/prompts'))[0]; await api.denied('GET', `/prompts/${foreign.id}`, undefined, [404]);
});
test('requirements follow draft, approval, plan and development states', async ({ api, f }) => {
  const r = await api.requirement(); expect((await api.get(`/requirements/${r.id}`)).status).toBe('DRAFT');
  await api.post(`/requirements/${r.id}/submit`); expect((await api.get(`/requirements/${r.id}`)).status).toBe('APPROVING');
  await api.post(`/requirements/${r.id}/approve`); expect((await api.get(`/requirements/${r.id}`)).status).toBe('PENDING_PLAN');
  await api.post(`/requirements/${r.id}/plan`, { releaseVersionId: f.versionId });
  await api.post(`/requirements/${r.id}/develop`); expect((await api.get(`/requirements/${r.id}`)).status).toBe('DEVELOPING');
});
test('requirement rejection returns to editable state and may be resubmitted', async ({ api }) => {
  const r = await api.requirement(); await api.post(`/requirements/${r.id}/submit`);
  await api.post(`/requirements/${r.id}/reject`, { reason: 'Need acceptance criteria' });
  expect((await api.get(`/requirements/${r.id}`)).status).toBe('REJECTED'); await api.post(`/requirements/${r.id}/submit`);
});
test('illegal requirement transition is rejected without state change', async ({ api }) => {
  const r = await api.requirement(); await api.denied('POST', `/requirements/${r.id}/develop`); expect((await api.get(`/requirements/${r.id}`)).status).toBe('DRAFT');
});
test('requirements cannot reference another tenants product', async ({ api, f }) => {
  await api.denied('POST', '/requirements', { productId: f.otherProductId, productVersionId: f.versionId, name: 'Invalid', description: 'No cross tenant references' });
});
test('issue state updates and content persist', async ({ api }) => {
  const issue = await api.issue(); expect((await api.get(`/issues/${issue.id}`)).status).toBe('OPEN');
  await api.post(`/issues/${issue.id}/status`, { status: 'IN_PROGRESS' }); expect((await api.get(`/issues/${issue.id}`)).status).toBe('IN_PROGRESS');
  await api.post(`/issues/${issue.id}/status`, { status: 'DONE' }); expect((await api.get(`/issues/${issue.id}`)).status).toBe('DONE');
});
test('invalid issue state is rejected', async ({ api }) => { const issue = await api.issue(); await api.denied('POST', `/issues/${issue.id}/status`, { status: 'INVALID' }); });
test('cross-tenant issue access is rejected', async ({ api, f }) => {
  const issue = await api.issue(); await api.as('other', f.otherTenantId).denied('GET', `/issues/${issue.id}`, undefined, [404]);
});
test('non-member cannot be assigned as issue handler', async ({ api, f }) => { await api.denied('POST', '/issues', { productId: f.productId, description: 'Bad handler', handlerId: f.users.outsider.id }); });
test('platform role permission configuration round-trips', async ({ api }) => {
  const admin = api.as('admin'); const role = await admin.post('/roles', { name: unique('ReadRole') });
  await admin.put(`/roles/${role.id}/permissions`, ['product:view', 'prompt:view']);
  expect((await admin.get(`/roles/${role.id}/permissions`)).sort()).toEqual(['product:view', 'prompt:view']);
});
test('operator cannot modify platform roles', async ({ api }) => { await api.as('operator').denied('POST', '/roles', { name: unique('Forbidden') }, [403]); });
test('tenant member lists are scoped', async ({ api, f }) => {
  const members = await api.get('/tenant-users'); expect(members.some((m: any) => m.id === f.users.operator.id || m.userId === f.users.operator.id)).toBeTruthy();
  expect(members.some((m: any) => m.id === f.users.other.id || m.userId === f.users.other.id)).toBeFalsy();
});
