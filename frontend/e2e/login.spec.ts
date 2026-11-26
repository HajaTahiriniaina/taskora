import { expect, test } from '@playwright/test';

import { apiMe, createAccount, randomPassword, uniqueEmail } from './support/accounts';
import { hasStoredToken, readStoredToken, SESSION_EXPIRED_TOAST } from './support/session';

// Page /login (docs/specs/pages.md §2).

test.describe('Connexion — /login', () => {
  test(
    'SC-IDENT-007 — connexion nominale (e-mail dans une autre casse) : /boards, jeton accepté',
    { tag: '@identity' },
    async ({ page, request }) => {
      const account = await createAccount(request, 'SC-IDENT-007');

      await page.goto('/login');
      await page.getByLabel('Adresse e-mail').fill(account.email.toUpperCase());
      await page.getByLabel('Mot de passe', { exact: true }).fill(account.password);
      await page.getByRole('button', { name: 'Se connecter' }).click();

      await expect(page).toHaveURL(/\/boards$/);
      await expect(page.getByRole('button', { name: 'Menu utilisateur' })).toBeVisible();

      const token = await readStoredToken(page);
      expect(token !== null, 'taskora.token présent').toBe(true);
      const me = await apiMe(request, token ?? '');
      expect(me.status(), 'GET /api/me avec le jeton stocké').toBe(200);
      expect(((await me.json()) as { email: string }).email).toBe(account.email);
    },
  );

  test(
    'SC-IDENT-008 — mauvais mot de passe et e-mail inconnu : même message, mêmes corps 401, pas de toast de session',
    { tag: '@identity' },
    async ({ page, request }) => {
      const account = await createAccount(request, 'SC-IDENT-008');
      const attempts = [
        { email: account.email, label: 'mauvais mot de passe', password: randomPassword() },
        {
          email: uniqueEmail('SC-IDENT-008-inconnu'),
          label: 'e-mail inconnu',
          password: randomPassword(),
        },
      ];
      const bodies: string[] = [];

      await page.goto('/login');
      for (const attempt of attempts) {
        await page.getByLabel('Adresse e-mail').fill(attempt.email);
        await page.getByLabel('Mot de passe', { exact: true }).fill(attempt.password);
        const responsePromise = page.waitForResponse((r) => r.url().endsWith('/api/auth/login'));
        await page.getByRole('button', { name: 'Se connecter' }).click();
        const response = await responsePromise;

        expect(response.status(), attempt.label).toBe(401);
        bodies.push(await response.text());
        await expect(
          page.getByText('E-mail ou mot de passe incorrect.', { exact: true }),
        ).toBeVisible();
        // Aucun toast SweetAlert2, en particulier pas celui de session. L'intercepteur le crée avant
        // que le composant n'affiche son message : on compte donc tout de suite, sans assertion
        // à réessai (le toast disparaît seul après quelques secondes et la masquerait).
        expect(await page.locator('.swal2-container').count(), 'aucun toast').toBe(0);
        expect(await page.getByText(SESSION_EXPIRED_TOAST).count(), 'pas de toast de session').toBe(
          0,
        );
        await expect(page).toHaveURL(/\/login$/);
        expect(await hasStoredToken(page), `taskora.token absent (${attempt.label})`).toBe(false);
      }
      expect(bodies[0], 'corps 401 identiques').toBe(bodies[1]);
    },
  );
});
