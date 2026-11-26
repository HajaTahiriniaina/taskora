import { defineConfig, devices } from '@playwright/test';

// E2E Taskora (docs/architecture/frontend.md §3.2 et §4) : les tests passent par nginx.
// Aucune lecture de .env : seules les variables E2E_* et CI de l'environnement sont lues.
const env = process.env;
const backend = env['E2E_BACKEND'] ?? 'local';

export default defineConfig({
  forbidOnly: !!env['CI'],
  fullyParallel: true,
  metadata: { backend },
  outputDir: 'reports/e2e-artifacts',
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  reporter: [
    ['list'],
    [
      'html',
      { open: 'never', outputFolder: 'reports/e2e-html', title: `Taskora e2e — ${backend}` },
    ],
    ['junit', { outputFile: 'reports/e2e-junit.xml' }],
  ],
  retries: 0,
  testDir: 'e2e',
  use: {
    baseURL: env['E2E_BASE_URL'] ?? 'http://127.0.0.1:8080',
    locale: 'fr-FR',
    screenshot: 'only-on-failure',
    // Traces : elles contiennent l'en-tête Authorization et localStorage, donc jamais en CI.
    trace: env['E2E_TRACE'] === 'on' ? 'retain-on-failure' : 'off',
    video: 'off',
  },
  workers: env['CI'] ? 2 : undefined,
});
