import { defineConfig } from '@playwright/test';
import fs from 'node:fs';
export default defineConfig({
  testDir: './tests', timeout: 60000, expect: { timeout: 10000 },
  fullyParallel: false, workers: 1, retries: 0, forbidOnly: !!process.env.CI,
  reporter: [['list'], ['html', { open: 'never' }], ['junit', { outputFile: 'reports/junit.xml' }], ['json', { outputFile: 'reports/results.json' }]],
  use: { baseURL: process.env.DAP_TEST_UI_URL, browserName: 'chromium',
    channel: process.env.DAP_TEST_BROWSER_CHANNEL || (process.platform === 'win32' && fs.existsSync('C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe') ? 'msedge' : undefined),
    headless: true, trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  projects: [
    { name: 'api', testMatch: '**/api/**/*.spec.ts' },
    { name: 'ui', testMatch: '**/ui/**/*.spec.ts' },
    { name: 'workflow', testMatch: '**/workflow/**/*.spec.ts' },
  ],
});
