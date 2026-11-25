import fs from 'node:fs/promises';
import path from 'node:path';

// 开发环境数据库长期保留：账号、租户编码均带本次运行前缀，避免与已有数据或上次运行冲突。
export async function provision({ backend, runRoot, token, mock, id, username, password }) {
  const prefix = `t${id}`;
  async function call(route, data, auth, tenant, method = 'POST') {
    const response = await fetch(`${backend}/api${route}`, { method, headers: { 'Content-Type': 'application/json', ...(auth ? { Authorization: `Bearer ${auth}` } : {}), ...(tenant ? { 'X-Tenant-Id': String(tenant) } : {}) }, ...(data === undefined ? {} : { body: JSON.stringify(data) }) });
    const body = await response.json(); if (body.code !== 0) throw new Error(`Fixture ${route}: ${body.message}`); return body.data;
  }
  const login = async (name, secret = 'DapTest_123!') => call('/auth/login', { username: name, password: secret });
  const admin = await login(username, password);
  const users = {};
  for (const name of ['owner', 'other', 'operator', 'outsider']) {
    const account = `${prefix}_${name}`;
    const user = await call('/users', { username: account, password: 'DapTest_123!', roleId: 2 }, admin.token);
    users[name] = { ...await login(account), id: user.id, username: account, password: 'DapTest_123!' };
  }
  const tenant = await call('/tenants', { code: `${prefix}_a`, name: `Test Tenant A ${prefix}`, status: 1, adminUserIds: [admin.user.id, users.owner.id] }, admin.token);
  const otherTenant = await call('/tenants', { code: `${prefix}_b`, name: `Test Tenant B ${prefix}`, status: 1, adminUserIds: [admin.user.id, users.other.id] }, admin.token);
  await call('/tenant-users', { userId: users.operator.id, tenantRole: 'OPERATOR' }, admin.token, tenant.id);
  // 路径绑定用例只需要目录；Git 基线由仓库工作流按需创建。
  const repoPath = path.join(runRoot, 'repository');
  await fs.mkdir(repoPath, { recursive: true });
  const product = await call('/products', { code: 'TESTAPP', name: 'Test Product', managerUserId: users.owner.id, codeRepos: [{ localPath: repoPath, repoKind: 'BIZ' }] }, users.owner.token, tenant.id);
  const version = await call(`/products/${product.id}/versions`, { version: '1.0', status: 'PLANNING', planReleaseAt: '2030-01-01T00:00:00' }, users.owner.token, tenant.id);
  const otherProduct = await call('/products', { code: 'OTHERAPP', name: 'Other Product', managerUserId: users.other.id }, users.other.token, otherTenant.id);
  const workspace = await call('/workbench/workspaces', { name: 'test_workspace' }, users.owner.token, tenant.id);
  const wiki = await call('/workbench/wiki/dirs', { displayName: 'Test Documents' }, users.owner.token, tenant.id);
  await call(`/products/${product.id}`, { code: 'TESTAPP', name: 'Test Product', managerUserId: users.owner.id, wikiNodeId: wiki.id, codeRepos: [{ localPath: repoPath, repoKind: 'BIZ' }] }, users.owner.token, tenant.id, 'PUT');
  const tree = await call('/workbench/wiki/tree', undefined, users.owner.token, tenant.id, 'GET');
  const templates = tree.find(n => n.displayName === '模板') || await call('/workbench/wiki/dirs', { displayName: '模板' }, users.owner.token, tenant.id);
  const templateDir = path.resolve('..', 'doc', '模板');
  for (const name of await fs.readdir(templateDir)) {
    if (name.endsWith('.md') && !(templates.children || []).some(n => n.displayName === name)) await call('/workbench/wiki/files', { parentId: templates.id, displayName: name, content: await fs.readFile(path.join(templateDir, name), 'utf8') }, users.owner.token, tenant.id);
  }
  const standards = tree.find(n => n.displayName === '规范') || await call('/workbench/wiki/dirs', { displayName: '规范' }, users.owner.token, tenant.id);
  await call('/workbench/wiki/files', { parentId: standards.id, displayName: '测试规范.md', content: '# Fixture standards\nUse minimal changes and deterministic tests.' }, users.owner.token, tenant.id);
  await call('/positions/ensure-builtin', {}, users.owner.token, tenant.id);
  await call('/prompts/ensure-pre-review', {}, users.owner.token, tenant.id);
  await call(`/work/products/${product.id}/sandbox/ensure`, {}, users.owner.token, tenant.id);
  return { id, prefix, tenantName: tenant.name, otherProductCode: 'OTHERAPP', backend, mock, token, runRoot, repoPath, admin, users, tenantId: tenant.id, otherTenantId: otherTenant.id, productId: product.id, versionId: version.id, otherProductId: otherProduct.id, workspaceId: workspace.id, wikiId: wiki.id };
}
