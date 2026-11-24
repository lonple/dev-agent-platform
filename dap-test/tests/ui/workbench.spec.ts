import { test, expect } from '../../support/fixtures.js';

test('unauthenticated navigation redirects to login', async ({ page }) => {
  await page.goto('/tenant-config/products'); await expect(page).toHaveURL(/\/login/); await expect(page.getByRole('button', { name: '登录', exact: true })).toBeVisible();
});
test('login form reaches authenticated overview', async ({ page, f }) => {
  await page.goto('/login'); await page.getByRole('textbox').first().fill(f.users.owner.username);
  await page.locator('input[type=password]').fill(f.users.owner.password); await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page).toHaveURL(/\/home/); await expect(page.getByRole('heading', { name: '工作总览' })).toBeVisible();
});

test.describe('authenticated UI', () => {
  test.beforeEach(async ({ page, f }) => {
    await page.addInitScript(({ token, tenant, name }) => { localStorage.setItem('dap_token', token); localStorage.setItem('dap_tenant_id', String(tenant)); localStorage.setItem('dap_tenant_name', name); }, { token: f.users.owner.token, tenant: f.tenantId, name: f.tenantName });
  });
  test('product editor displays repository purpose and preserves TEST selection', async ({ page, api, f }) => {
    const p = await api.product({ code: 'UIREPO', name: 'UI Repository Product', codeRepos: [{ localPath: f.repoPath, repoKind: 'TEST' }] });
    await page.goto('/tenant-config/products'); await page.getByRole('button', { name: 'UI Repository Product UIREPO', exact: true }).click();
    await page.getByRole('button', { name: '编辑', exact: true }).first().click();
    const dialog = page.getByRole('dialog'); await expect(dialog.getByRole('combobox', { name: '仓库用途' })).toBeVisible();
    await expect(dialog.getByText('自动化测试', { exact: true })).toBeVisible(); await dialog.getByRole('button', { name: '保存', exact: true }).click();
    await expect(dialog).not.toBeVisible(); expect((await api.get(`/products/${p.id}`)).codeRepos[0].repoKind).toBe('TEST');
  });
  test('prompt manager separates requirement and issue workflows', async ({ page }) => {
    await page.goto('/tenant-config/prompts'); await page.getByRole('tab', { name: /问题修复/ }).click();
    await expect(page.locator('.node-list .node-item').filter({ hasText: '问题分析' })).toBeVisible();
    await expect(page.locator('.node-list .node-item').filter({ hasText: '生成需求分析' })).toHaveCount(0);
    await page.getByRole('tab', { name: /开发需求/ }).click(); await expect(page.locator('.node-list .node-item').filter({ hasText: '生成需求分析' })).toBeVisible();
  });
  test('unsaved prompt edits cannot be silently lost when changing workflow', async ({ page }) => {
    await page.goto('/tenant-config/prompts'); await page.getByRole('button', { name: '编辑内容', exact: true }).click();
    await page.locator('.md-editor textarea').fill('# unsaved test edit'); await page.getByRole('tab', { name: /问题修复/ }).click();
    await expect(page.getByText('当前提示词有未保存的修改，确定放弃修改并继续？')).toBeVisible();
    await page.getByRole('button', { name: '继续编辑', exact: true }).click(); await expect(page.locator('.md-editor textarea')).toHaveValue('# unsaved test edit');
  });
  test('Agent role links to its exact task prompt', async ({ page }) => {
    await page.goto('/tenant-config/positions'); await page.getByRole('button', { name: /研发.*内置岗位/ }).click();
    await page.getByRole('tab', { name: /问题修复/ }).click(); await page.getByRole('button', { name: '查看提示词', exact: true }).first().click();
    await expect(page).toHaveURL(/tenant-config\/prompts\?.*positionId=/); await expect(page.getByRole('heading', { name: '问题分析', exact: true })).toBeVisible();
  });
});
