import { expect, test } from '@playwright/test';

import { createAccount } from './support/accounts';
import { tamperStoredTokenSignature } from './support/jwt';
import { hasStoredToken, loginViaUi, SESSION_EXPIRED_TOAST } from './support/session';

// Coquille de l'application : expiration de session (docs/specs/pages.md §4).

test(
  "SC-IDENT-025 — jeton refusé par l'API : retour à /login, jeton supprimé, toast de session",
  { tag: '@identity' },
  async ({ page, request }) => {
    const account = await createAccount(request, 'SC-IDENT-025');
    await loginViaUi(page, account.email, account.password);

    // exp reste valide : la garde laisse passer, c'est l'API qui refuse la signature.
    await tamperStoredTokenSignature(page);
    const refused = page.waitForResponse((r) => r.url().includes('/api/') && r.status() === 401);
    await page.goto('/boards');
    await refused;

    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('alert').filter({ hasText: SESSION_EXPIRED_TOAST })).toBeVisible();
    expect(await hasStoredToken(page), 'taskora.token supprimé').toBe(false);
  },
);
