import { defineConfig, devices } from '@playwright/test';

/**
 * Browser-driven end-to-end tests (`npm run e2e`).
 *
 * These run against the **compose stack**, not against `ng serve`: the point of
 * the suite (docs/verification.md 4.5) is to exercise the request the shipped
 * bundle issues to the real identity provider, which a dev server with mocked
 * HTTP cannot do. Bring the stack up first:
 *
 *     cd containers && ./scripts/generate-certs.sh && podman compose up -d
 *
 * `HIVE_E2E_BASE_URL` overrides the origin for a stack published somewhere else.
 * There is deliberately no `webServer` entry -- Playwright must not start or own
 * the thing under test, because a stack it started would be a stack configured
 * by this file rather than the one that ships.
 */
const baseURL = process.env['HIVE_E2E_BASE_URL'] ?? 'https://localhost:8444';

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.e2e.ts',
  globalSetup: './e2e/support/global-setup.ts',
  // The whole stack -- one database, one realm, one set of users -- is shared
  // state, so tests run one at a time and never retry into a half-finished
  // session.
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env['CI'],
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  reporter: process.env['CI'] ? [['list'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL,
    // The stack presents certificates from containers/scripts/generate-certs.sh,
    // signed by a CA that only the developer's machine trusts. Accepting them
    // here keeps the suite runnable before that CA is installed; it does not
    // weaken anything under test, because nothing here asserts on trust.
    ignoreHTTPSErrors: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
