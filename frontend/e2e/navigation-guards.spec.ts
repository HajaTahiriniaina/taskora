import { expect, Page, test } from '@playwright/test';

import { createAccount } from './support/accounts';
import { storeExpiredToken, storeUnreadableToken } from './support/jwt';
import { storeSession } from './support/session';

// Gardes et redirections (docs/specs/pages.md §1–4, SC-IDENT-026).

const ROOT_URL = /^https?:\/\/[^/]+\/$/;

/**
 * Appels à l'API émis par la page. La garde doit rediriger AVANT tout appel : sinon c'est le 401
 * de l'intercepteur (SC-IDENT-025), et non la garde, qui ramènerait à /login.
 */
function recordApiCalls(page: Page): string[] {
  const calls: string[] = [];
  page.on('request', (request) => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/')) calls.push(`${request.method()} ${url.pathname}`);
  });
  return calls;
}

for (const path of ['/boards', '/boards/12', '/profile']) {
  test(
    `SC-IDENT-026 — visiteur anonyme : /boards, /boards/12 et /profile redirigent vers /login [${path}]`,
    { tag: '@identity' },
    async ({ page }) => {
      const apiCalls = recordApiCalls(page);
      await page.goto(path);
      await expect(page).toHaveURL(/\/login$/);
      await expect(page.getByRole('button', { name: 'Se connecter' })).toBeVisible();
      expect(apiCalls, 'aucun appel API avant la redirection').toEqual([]);
    },
  );
}

const forged = [
  { label: 'expiré', store: storeExpiredToken },
  { label: 'illisible', store: storeUnreadableToken },
];
for (const { label, store } of forged) {
  test(
    `SC-IDENT-026 — jeton stocké expiré ou illisible : redirection vers /login [${label}]`,
    { tag: '@identity' },
    async ({ page }) => {
      await page.goto('/');
      await store(page);
      const apiCalls = recordApiCalls(page);
      await page.goto('/boards');
      await expect(page).toHaveURL(/\/login$/);
      await expect(page.getByRole('button', { name: 'Se connecter' })).toBeVisible();
      expect(apiCalls, 'aucun appel API avant la redirection').toEqual([]);
    },
  );
}

test(
  'SC-IDENT-026 — utilisateur connecté : /, /login et /register redirigent vers /boards ; route inconnue → /',
  { tag: '@identity' },
  async ({ page, request }) => {
    const account = await createAccount(request, 'SC-IDENT-026');
    await storeSession(page, account);
    for (const path of ['/', '/login', '/register']) {
      await page.goto(path);
      await expect(page, `connecté sur ${path}`).toHaveURL(/\/boards$/);
    }

    await page.evaluate(() => localStorage.clear());
    await page.goto('/route-inconnue-e2e');
    await expect(page, 'anonyme sur une route inconnue').toHaveURL(ROOT_URL);
  },
);
