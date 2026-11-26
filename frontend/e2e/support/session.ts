import { expect, Page } from '@playwright/test';

import { PublicUser, TestAccount } from './accounts';

// Session front : jeton et profil dans localStorage (taskora.token, taskora.user).

export const TOKEN_KEY = 'taskora.token';
export const USER_KEY = 'taskora.user';
export const SESSION_EXPIRED_TOAST = 'Votre session a expiré, reconnectez-vous.';

/** Connexion par l'UI de /login, puis attente de /boards. */
export async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Adresse e-mail').fill(email);
  await page.getByLabel('Mot de passe', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Se connecter' }).click();
  await expect(page).toHaveURL(/\/boards$/);
}

/** Dépose jeton et profil dans localStorage (origine de l'application), sans passer par l'UI. */
export async function storeSession(page: Page, account: TestAccount): Promise<void> {
  await page.goto('/');
  await page.evaluate(
    ([tokenKey, userKey, token, user]) => {
      localStorage.setItem(tokenKey, token);
      localStorage.setItem(userKey, user);
    },
    [TOKEN_KEY, USER_KEY, account.token, JSON.stringify(account.user)],
  );
}

export function readStoredToken(page: Page): Promise<null | string> {
  return page.evaluate((key) => localStorage.getItem(key), TOKEN_KEY);
}

export async function readStoredUser(page: Page): Promise<null | PublicUser> {
  const raw = await page.evaluate((key) => localStorage.getItem(key), USER_KEY);
  return raw ? (JSON.parse(raw) as PublicUser) : null;
}
