import fs from 'node:fs';
import { test as base, expect } from '@playwright/test';

let sequence = 0;
export const unique = (prefix: string) => `${prefix}_${Date.now()}_${++sequence}`;
export class Api {
  readonly instances: number[] = [];
  constructor(readonly f: any, readonly actor = 'owner', readonly tenant = f.tenantId) {}
  as(actor: string, tenant = this.tenant) { return new Api(this.f, actor, tenant); }
  async raw(method: string, route: string, data?: unknown) {
    const user = this.actor === 'admin' ? this.f.admin : this.f.users[this.actor];
    const response = await fetch(`${this.f.backend}/api${route}`, {
      method, headers: { 'Content-Type': 'application/json', ...(user ? { Authorization: `Bearer ${user.token}` } : {}), ...(this.tenant ? { 'X-Tenant-Id': String(this.tenant) } : {}) },
      ...(data === undefined ? {} : { body: JSON.stringify(data) }),
    });
    return { status: response.status, body: await response.json() };
  }
  async call(method: string, route: string, data?: unknown): Promise<any> {
    const result = await this.raw(method, route, data);
    expect(result.body.code, `${method} ${route}: ${result.body.message}`).toBe(0);
    return result.body.data;
  }
  get(route: string) { return this.call('GET', route); }
  post(route: string, data: unknown = {}) { return this.call('POST', route, data); }
  put(route: string, data: unknown) { return this.call('PUT', route, data); }
  delete(route: string) { return this.call('DELETE', route); }
  async denied(method: string, route: string, data?: unknown, codes?: number[]) {
    const { body } = await this.raw(method, route, data);
    expect(body.code, `${method} ${route} unexpectedly succeeded`).not.toBe(0);
    if (codes) expect(codes).toContain(body.code);
    return body;
  }
  async product(overrides = {}) { return this.post('/products', { code: unique('P'), name: unique('Product'), managerUserId: this.f.users.owner.id, ...overrides }); }
  async requirement() { return this.post('/requirements', { name: unique('Requirement'), productId: this.f.productId, productVersionId: this.f.versionId, description: 'Given an order, users can inspect its status.' }); }
  async issue() { return this.post('/issues', { productId: this.f.productId, description: unique('Issue'), steps: 'Open the detail page', expectedResult: 'Expected data is displayed', handlerId: this.f.users.owner.id }); }
  async pipeline(issueId: number) {
    const value = await this.post('/pipeline/instances', { issueId });
    const id = value.id ?? value.instanceId;
    this.instances.push(id); return id as number;
  }
  async scenario(value: Record<string, unknown>) {
    const response = await fetch(`${this.f.mock}/__scenario`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Test-Token': this.f.token }, body: JSON.stringify(value) });
    expect(response.ok).toBeTruthy();
  }
  async mockState() { return fetch(`${this.f.mock}/__state`, { headers: { 'X-Test-Token': this.f.token } }).then(r => r.json()); }
  async waitUnit(instanceId: number, task: string, statuses: string[], timeout = 30000) {
    let unit: any;
    await expect.poll(async () => {
      const instance = await this.get(`/pipeline/instances/${instanceId}`);
      unit = instance.nodes.flatMap((n: any) => n.units).find((u: any) => u.taskTypeCode === task);
      return unit?.status;
    }, { timeout, intervals: [200, 500, 1000] }).toMatch(new RegExp(`^(${statuses.join('|')})$`));
    return unit;
  }
}

export const test = base.extend<{ api: Api; f: any }>({
  f: async ({}, use: (value: any) => Promise<void>) => {
    const file = process.env.DAP_TEST_FIXTURES;
    if (!file) throw new Error('Use npm test; direct execution against a business instance is not supported.');
    await use(JSON.parse(fs.readFileSync(file, 'utf8')));
  },
  api: async ({ f }, use) => {
    const api = new Api(f);
    await use(api);
    for (const id of api.instances) { try { await api.post(`/pipeline/instances/${id}/cancel`); } catch {} }
  },
});
export { expect };
