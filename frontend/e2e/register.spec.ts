import { expect, test } from '@playwright/test';

import {
  apiMe,
  AVATAR_PALETTE,
  createAccount,
  randomPassword,
  uniqueEmail,
} from './support/accounts';
import { hasStoredToken, readStoredToken, readStoredUser } from './support/session';

// Page /register (docs/specs/pages.md §3).

test.describe('Inscription — /register', () => {
  test(
    'SC-IDENT-001 — inscription nominale : /boards, nom et e-mail normalisés, couleur de la palette, réponse sans mot de passe',
    { tag: '@identity' },
    async ({ page, request }) => {
      const email = uniqueEmail('SC-IDENT-001');
      const mixedCaseEmail = email.replace(/^e2e/, 'E2E').replace('@example.com', '@Example.COM');
      const name = `E2E SC-IDENT-001 ${Date.now()}`;

      await page.goto('/register');
      await page.getByLabel('Nom complet').fill(`   ${name}  `);
      await page.getByLabel('Adresse e-mail').fill(mixedCaseEmail);
      await page.getByLabel('Mot de passe', { exact: true }).fill(randomPassword());
      const responsePromise = page.waitForResponse(
        (r) => r.url().endsWith('/api/auth/register') && r.request().method() === 'POST',
      );
      await page.getByRole('button', { name: 'Créer mon compte' }).click();
      const response = await responsePromise;

      expect(response.status()).toBe(201);
      const raw = await response.text();
      expect(raw.toLowerCase(), 'réponse sans mot de passe ni hash').not.toContain('password');
      const body = JSON.parse(raw) as { token?: unknown; user: Record<string, unknown> };
      expect(typeof body.token).toBe('string');
      expect(Object.keys(body.user).sort()).toEqual(['avatarColor', 'email', 'id', 'name']);
      expect(body.user['name'], 'nom réduit par trim (réponse)').toBe(name);
      expect(body.user['email'], 'e-mail en minuscules (réponse)').toBe(email);
      expect(AVATAR_PALETTE).toContain(body.user['avatarColor']);

      await expect(page).toHaveURL(/\/boards$/);
      const stored = await readStoredUser(page);
      expect(stored?.name, 'nom réduit par trim').toBe(name);
      expect(stored?.email, 'e-mail en minuscules').toBe(email);
      expect(AVATAR_PALETTE).toContain(stored?.avatarColor);

      const token = await readStoredToken(page);
      expect(token !== null, 'taskora.token présent').toBe(true);
      const me = await apiMe(request, token ?? '');
      expect(me.status(), 'GET /api/me avec le jeton stocké').toBe(200);
      expect(((await me.json()) as { email: string }).email).toBe(email);
    },
  );

  test(
    'SC-IDENT-002 — e-mail déjà utilisé (autre casse) : message 409 sous le formulaire, aucune session',
    { tag: '@identity' },
    async ({ page, request }) => {
      const existing = await createAccount(request, 'SC-IDENT-002');

      await page.goto('/register');
      await page.getByLabel('Nom complet').fill('E2E SC-IDENT-002 doublon');
      await page.getByLabel('Adresse e-mail').fill(existing.email.toUpperCase());
      await page.getByLabel('Mot de passe', { exact: true }).fill(randomPassword());
      const responsePromise = page.waitForResponse((r) => r.url().endsWith('/api/auth/register'));
      await page.getByRole('button', { name: 'Créer mon compte' }).click();

      expect((await responsePromise).status()).toBe(409);
      // Message annoncé (role="alert", identity.md §6, recette m6), texte exact.
      const duplicate = 'Un compte existe déjà avec cette adresse e-mail.';
      const duplicateAlert = page.getByRole('alert').filter({ hasText: duplicate });
      await expect(duplicateAlert).toBeVisible();
      await expect(duplicateAlert).toHaveText(duplicate);
      await expect(page).toHaveURL(/\/register$/);
      expect(await hasStoredToken(page), 'taskora.token absent').toBe(false);
    },
  );

  test(
    "SC-IDENT-003 — nom composé d'espaces : message de l'API affiché",
    { tag: '@identity' },
    async ({ page }) => {
      await page.goto('/register');
      await page.getByLabel('Nom complet').fill('     ');
      await page.getByLabel('Adresse e-mail').fill(uniqueEmail('SC-IDENT-003'));
      await page.getByLabel('Mot de passe', { exact: true }).fill(randomPassword());
      const responsePromise = page.waitForResponse((r) => r.url().endsWith('/api/auth/register'));
      await page.getByRole('button', { name: 'Créer mon compte' }).click();

      expect((await responsePromise).status()).toBe(400);
      // Message annoncé (role="alert", identity.md §6, recette m6), texte exact.
      const required = 'Le champ "name" est obligatoire.';
      const requiredAlert = page.getByRole('alert').filter({ hasText: required });
      await expect(requiredAlert).toBeVisible();
      await expect(requiredAlert).toHaveText(required);
      await expect(page).toHaveURL(/\/register$/);
      expect(await hasStoredToken(page), 'taskora.token absent').toBe(false);
    },
  );
});
