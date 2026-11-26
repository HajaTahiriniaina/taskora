import { expect, test } from '@playwright/test';

import { apiLogin, createAccount, randomPassword } from './support/accounts';
import { hasStoredToken, loginViaUi } from './support/session';

// Page /profile, section Sécurité (docs/specs/pages.md §7).

test.describe('Profil — /profile', () => {
  test(
    'SC-IDENT-017 — changement de mot de passe : toast, champs vidés, nouveau accepté, ancien refusé',
    { tag: '@identity' },
    async ({ page, request }) => {
      const account = await createAccount(request, 'SC-IDENT-017');
      const newPassword = randomPassword();
      await loginViaUi(page, account.email, account.password);
      await page.goto('/profile');

      const current = page.getByLabel('Mot de passe actuel', { exact: true });
      const next = page.getByLabel('Nouveau mot de passe', { exact: true });
      await current.fill(account.password);
      await next.fill(newPassword);
      await page.getByRole('button', { name: 'Changer le mot de passe' }).click();

      await expect(
        page.getByRole('alert').filter({ hasText: 'Mot de passe modifié' }),
      ).toBeVisible();
      expect((await apiLogin(request, account.email, newPassword)).status(), 'nouveau').toBe(200);
      expect((await apiLogin(request, account.email, account.password)).status(), 'ancien').toBe(
        401,
      );
      // Longueur seulement : la valeur d'un champ mot de passe ne doit pas finir dans un rapport.
      await expect
        .poll(async () => (await current.inputValue()).length, {
          message: 'champ « Mot de passe actuel » vidé',
        })
        .toBe(0);
      await expect
        .poll(async () => (await next.inputValue()).length, {
          message: 'champ « Nouveau mot de passe » vidé',
        })
        .toBe(0);
    },
  );

  test(
    "SC-IDENT-018 — mot de passe actuel faux : toast d'erreur, mot de passe inchangé",
    { tag: '@identity' },
    async ({ page, request }) => {
      const account = await createAccount(request, 'SC-IDENT-018');
      const newPassword = randomPassword();
      await loginViaUi(page, account.email, account.password);
      await page.goto('/profile');

      await page.getByLabel('Mot de passe actuel', { exact: true }).fill(randomPassword());
      await page.getByLabel('Nouveau mot de passe', { exact: true }).fill(newPassword);
      const responsePromise = page.waitForResponse(
        (r) => r.url().endsWith('/api/me') && r.request().method() === 'PATCH',
      );
      await page.getByRole('button', { name: 'Changer le mot de passe' }).click();

      expect((await responsePromise).status()).toBe(400);
      await expect(
        page.getByRole('alert').filter({ hasText: 'Mot de passe actuel incorrect.' }),
      ).toBeVisible();
      await expect(page).toHaveURL(/\/profile$/);
      expect(await hasStoredToken(page), 'toujours connecté').toBe(true);
      expect((await apiLogin(request, account.email, account.password)).status(), 'ancien').toBe(
        200,
      );
      expect((await apiLogin(request, account.email, newPassword)).status(), 'nouveau').toBe(401);
    },
  );
});
