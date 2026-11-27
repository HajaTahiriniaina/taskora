import { APIRequestContext, expect, Page, Response, test } from '@playwright/test';

import { apiLogin, apiMe, createAccount, PublicUser, randomPassword } from './support/accounts';
import { hasStoredToken, loginViaUi, readStoredUser, storeSession } from './support/session';

// Page /profile : sections Informations et Sécurité (docs/specs/pages.md §7).

const PROFILE_SAVED_TOAST = 'Profil mis à jour';
/** Format strict de identity-service (décision E18) : exactement #RRGGBB. */
const STRICT_COLOR = /^#[0-9a-fA-F]{6}$/;

function isMe(method: 'GET' | 'PATCH') {
  return (r: Response) => r.url().endsWith('/api/me') && r.request().method() === method;
}

/** Clic sur « Enregistrer » (section Informations) et réponse du PATCH /api/me. */
async function saveProfile(page: Page): Promise<Response> {
  const responsePromise = page.waitForResponse(isMe('PATCH'));
  await page.getByRole('button', { exact: true, name: 'Enregistrer' }).click();
  return responsePromise;
}

async function meFromApi(request: APIRequestContext, token: string): Promise<PublicUser> {
  const response = await apiMe(request, token);
  expect(response.status(), 'GET /api/me').toBe(200);
  return (await response.json()) as PublicUser;
}

test.describe('Profil — /profile, informations', () => {
  test(
    'SC-IDENT-014 — profil affiché = GET /api/me : taskora.user périmé remplacé, formulaire et e-mail à jour',
    { tag: '@identity' },
    async ({ page, request }) => {
      const account = await createAccount(request, 'SC-IDENT-014');
      // Profil périmé dans localStorage (renommage fait sur un autre appareil, par exemple).
      await storeSession(page, { ...account, user: { ...account.user, name: 'Nom périmé' } });

      const meResponse = page.waitForResponse(isMe('GET'));
      await page.goto('/profile');
      const me = await meResponse;
      expect(me.status()).toBe(200);
      const body = (await me.json()) as PublicUser;
      expect(Object.keys(body).sort()).toEqual(['avatarColor', 'email', 'id', 'name']);
      expect(body).toEqual(account.user);

      await expect.poll(() => readStoredUser(page)).toEqual(account.user);
      await expect(page.getByText(account.email, { exact: true })).toBeVisible();
      await expect(page.getByLabel('Nom complet')).toHaveValue(account.name);
      await expect(page.getByRole('heading', { level: 1, name: account.name })).toBeVisible();
    },
  );

  test(
    'SC-IDENT-015 — renommage : nom réduit par trim, toast « Profil mis à jour », persisté, en-tête mis à jour',
    { tag: '@identity' },
    async ({ page, request }) => {
      const account = await createAccount(request, 'SC-IDENT-015');
      await loginViaUi(page, account.email, account.password);
      await page.goto('/profile');

      await page.getByLabel('Nom complet').fill('  Camille M. ');
      const response = await saveProfile(page);
      expect(response.status()).toBe(200);
      expect(((await response.json()) as PublicUser).name).toBe('Camille M.');

      await expect(page.getByRole('alert').filter({ hasText: PROFILE_SAVED_TOAST })).toBeVisible();
      expect((await meFromApi(request, account.token)).name, 'persisté').toBe('Camille M.');
      expect((await readStoredUser(page))?.name, 'taskora.user').toBe('Camille M.');

      // En-tête : menu utilisateur, après rechargement (profil relu par GET /api/me).
      await page.goto('/boards');
      await page.getByRole('button', { name: 'Menu utilisateur' }).click();
      await expect(page.getByText('Camille M.', { exact: true })).toBeVisible();
    },
  );

  for (const [label, empty] of [
    ['vide', ''],
    ['espaces seulement', '   '],
  ] as const) {
    test(
      `SC-IDENT-015 — nom ${label} : 400 « Le champ "name" est obligatoire. » en toast, nom inchangé`,
      { tag: '@identity' },
      async ({ page, request }) => {
        const account = await createAccount(request, 'SC-IDENT-015');
        await loginViaUi(page, account.email, account.password);
        await page.goto('/profile');

        await page.getByLabel('Nom complet').fill(empty);
        const response = await saveProfile(page);
        expect(response.status()).toBe(400);
        expect(await response.json()).toEqual({ error: 'Le champ "name" est obligatoire.' });

        await expect(
          page.getByRole('alert').filter({ hasText: 'Le champ "name" est obligatoire.' }),
        ).toBeVisible();
        await expect(page).toHaveURL(/\/profile$/);
        expect(await hasStoredToken(page), 'toujours connecté').toBe(true);
        expect((await meFromApi(request, account.token)).name, 'nom inchangé').toBe(account.name);
        expect((await readStoredUser(page))?.name, 'taskora.user').toBe(account.name);
      },
    );
  }

  test(
    'SC-IDENT-015 — {"name":null} : nom inchangé (200), toujours affiché sur la page Profil',
    { tag: '@identity' },
    async ({ page, request }) => {
      const account = await createAccount(request, 'SC-IDENT-015');
      const response = await request.patch('/api/me', {
        data: { name: null },
        headers: { Authorization: `Bearer ${account.token}` },
      });
      expect(response.status()).toBe(200);
      expect(((await response.json()) as PublicUser).name).toBe(account.name);

      await loginViaUi(page, account.email, account.password);
      await page.goto('/profile');
      await expect(page.getByLabel('Nom complet')).toHaveValue(account.name);
    },
  );

  test(
    'SC-IDENT-015 — 150 caractères saisis : champ limité à 120, nom stocké = 120 premiers caractères',
    { tag: '@identity' },
    async ({ page, request }) => {
      const account = await createAccount(request, 'SC-IDENT-015');
      const long = 'N'.repeat(100) + 'L'.repeat(50);
      await loginViaUi(page, account.email, account.password);
      await page.goto('/profile');

      const name = page.getByLabel('Nom complet');
      await expect(name).toHaveAttribute('maxlength', '120');
      await name.fill(long);
      expect((await name.inputValue()).length, 'saisie bornée').toBeLessThanOrEqual(120);
      const response = await saveProfile(page);
      expect(response.status()).toBe(200);

      await expect(page.getByRole('alert').filter({ hasText: PROFILE_SAVED_TOAST })).toBeVisible();
      expect((await meFromApi(request, account.token)).name).toBe(long.slice(0, 120));
      expect((await readStoredUser(page))?.name).toBe(long.slice(0, 120));
    },
  );

  test(
    'SC-IDENT-016 — couleur choisie dans la palette : envoyée et stockée telle quelle, nom inchangé',
    { tag: '@identity' },
    async ({ page, request }) => {
      const account = await createAccount(request, 'SC-IDENT-016');
      // #f97316 : palette du profil, jamais tirée à l'inscription (identity.md §1) → vrai changement.
      const chosen = '#f97316';
      await loginViaUi(page, account.email, account.password);
      await page.goto('/profile');

      await page.getByRole('button', { exact: true, name: chosen }).click();
      const response = await saveProfile(page);
      expect(response.status()).toBe(200);
      const sent = response.request().postDataJSON() as { avatarColor: string; name: string };
      expect(sent.avatarColor).toBe(chosen);

      await expect(page.getByRole('alert').filter({ hasText: PROFILE_SAVED_TOAST })).toBeVisible();
      const me = await meFromApi(request, account.token);
      expect(me.avatarColor, 'stockée telle quelle').toBe(chosen);
      expect(me.name, 'nom inchangé').toBe(account.name);
      expect((await readStoredUser(page))?.avatarColor).toBe(chosen);
    },
  );

  test(
    "SC-IDENT-016 — le sélecteur n'offre que des couleurs #RRGGBB (E18)",
    { tag: '@identity' },
    async ({ page, request }) => {
      const account = await createAccount(request, 'SC-IDENT-016');
      await loginViaUi(page, account.email, account.password);
      await page.goto('/profile');

      const swatches = page.getByRole('button', { name: /^#/ });
      await expect(swatches.first()).toBeVisible();
      const names = await swatches.evaluateAll((els) =>
        els.map((el) => el.getAttribute('aria-label') ?? ''),
      );
      expect(names.length).toBeGreaterThan(1);
      for (const name of names) expect(name, name).toMatch(STRICT_COLOR);
    },
  );

  test(
    "SC-IDENT-016 — couleur invalide envoyée à l'API : couleur inchangée (Symfony 200, identity-service 400 E18), page Profil toujours utilisable",
    { tag: '@identity' },
    async ({ page, request }) => {
      const account = await createAccount(request, 'SC-IDENT-016');
      for (const invalid of ['red', '#12345', '#1234567']) {
        const response = await request.patch('/api/me', {
          data: { avatarColor: invalid },
          headers: { Authorization: `Bearer ${account.token}` },
        });
        // Les deux amonts sont admis par la spécification ; seul le message de 400 est imposé.
        expect([200, 400], invalid).toContain(response.status());
        if (response.status() === 400) {
          expect(await response.json(), invalid).toEqual({ error: "Couleur d'avatar invalide." });
        }
        expect((await meFromApi(request, account.token)).avatarColor, invalid).toBe(
          account.user.avatarColor,
        );
      }

      await loginViaUi(page, account.email, account.password);
      await page.goto('/profile');
      const response = await saveProfile(page);
      expect(response.status()).toBe(200);
      expect((response.request().postDataJSON() as { avatarColor: string }).avatarColor).toMatch(
        STRICT_COLOR,
      );
      await expect(page.getByRole('alert').filter({ hasText: PROFILE_SAVED_TOAST })).toBeVisible();
      expect((await meFromApi(request, account.token)).avatarColor).toBe(account.user.avatarColor);
    },
  );
});

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
