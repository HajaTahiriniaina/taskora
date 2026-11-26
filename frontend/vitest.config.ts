// Configuration Vitest complémentaire, chargée par la cible `test` d'angular.json (runnerConfig).
// Le builder @angular/build:unit-test fournit le reste (jsdom, TestBed, compilation).
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    coverage: {
      provider: 'v8',
      reportOnFailure: true,
      reportsDirectory: 'reports/coverage',
    },
    environment: 'jsdom',
  },
});
