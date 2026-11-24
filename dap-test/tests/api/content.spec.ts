import { test, expect, unique } from '../../support/fixtures.js';

test('workspace files support create, read, update and delete', async ({ api, f }) => {
  const prefix = `/workbench/workspaces/${f.workspaceId}`;
  const file = await api.post(`${prefix}/files`, { displayName: unique('note') + '.md', content: '# Original' });
  expect((await api.get(`${prefix}/nodes/${file.id}/content`)).content).toBe('# Original');
  await api.put(`${prefix}/nodes/${file.id}/content`, { content: '# Updated' });
  expect((await api.get(`${prefix}/nodes/${file.id}/content`)).content).toBe('# Updated');
  await api.delete(`${prefix}/nodes/${file.id}`); await api.denied('GET', `${prefix}/nodes/${file.id}/content`);
});
test('personal workspace cannot be read by a different user', async ({ api, f }) => { await api.as('operator').denied('GET', `/workbench/workspaces/${f.workspaceId}/tree`); });
test('workspace filename traversal is rejected', async ({ api, f }) => { await api.denied('POST', `/workbench/workspaces/${f.workspaceId}/files`, { displayName: '../escape.md', content: 'No' }); });
test('personal output can be published to Wiki and read back', async ({ api, f }) => {
  const name = unique('published') + '.md'; const content = '# Published acceptance evidence';
  const file = await api.post(`/workbench/workspaces/${f.workspaceId}/files`, { displayName: name, content });
  const published = await api.post('/workbench/wiki/publish', { sourceWorkspaceId: f.workspaceId, sourceNodeId: file.id, targetParentId: f.wikiId });
  expect((await api.get(`/workbench/wiki/nodes/${published.id}/content`)).content).toBe(content);
});
test('Wiki content is tenant isolated', async ({ api, f }) => {
  const file = await api.post('/workbench/wiki/files', { parentId: f.wikiId, displayName: unique('private') + '.md', content: 'Tenant A' });
  await api.as('other', f.otherTenantId).denied('GET', `/workbench/wiki/nodes/${file.id}/content`);
});
test('runtime profiles are product scoped and use neutral defaults', async ({ api, f }) => {
  const profiles = await api.get(`/work/products/${f.productId}/sandbox/profiles`);
  const original = JSON.parse(profiles.find((p: any) => p.isDefault).configJson);
  expect(original.ports.services).toEqual([]); expect(original.envVars).toEqual([]);
  const p = await api.product(); await api.post(`/work/products/${p.id}/sandbox/ensure`);
  const code = unique('runtime'); await api.put(`/work/products/${p.id}/sandbox/profiles`, { profileCode: code, displayName: 'Other runtime', configJson: JSON.stringify({ ports: { services: [{ key: 'custom', containerPort: 9001 }] } }) });
  expect((await api.get(`/work/products/${f.productId}/sandbox/profiles`)).some((v: any) => v.profileCode === code)).toBeFalsy();
});
test('cross-tenant runtime profile reads must be rejected', async ({ api, f }) => { await api.as('other', f.otherTenantId).denied('GET', `/work/products/${f.productId}/sandbox/profiles`, undefined, [403, 404]); });
test('operator cannot mutate sandbox product settings', async ({ api, f }) => { await api.as('operator').denied('PUT', `/work/products/${f.productId}/sandbox/profiles`, { profileCode: 'unauthorized', configJson: '{}' }, [403]); });
test('runtime defaults are idempotent and default profile cannot be deleted', async ({ api, f }) => {
  const base = `/work/products/${f.productId}/sandbox`;
  const before = await api.get(`${base}/profiles`); await api.post(`${base}/ensure`);
  expect((await api.get(`${base}/profiles`)).map((p: any) => p.id)).toEqual(before.map((p: any) => p.id));
  await api.denied('DELETE', `${base}/profiles/default`);
});
test('API validates runtime JSON rather than silently saving invalid content', async ({ api, f }) => { await api.denied('PUT', `/work/products/${f.productId}/sandbox/profiles`, { profileCode: unique('bad'), configJson: '{invalid' }); });
test('operator cannot view tenant-wide usage audit', async ({ api }) => { await api.as('operator').denied('GET', '/audit/token-usage/by-user', undefined, [403]); });
test('tenant usage audit can be read by tenant admin', async ({ api }) => { expect(Array.isArray(await api.get('/audit/token-usage/by-user'))).toBeTruthy(); });
