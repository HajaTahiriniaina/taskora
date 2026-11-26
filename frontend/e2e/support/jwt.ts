import { Page } from '@playwright/test';

import { TOKEN_KEY } from './session';

// Jetons forgés pour SC-IDENT-025/026 (frontend.md §4.4) : construits ou modifiés DANS le
// navigateur, jamais écrits en littéral dans le code, jamais affichés ni joints aux rapports.

/** Altère la signature du jeton stocké ; l'en-tête et la charge utile (donc exp) restent intacts. */
export async function tamperStoredTokenSignature(page: Page): Promise<void> {
  await page.evaluate((key) => {
    const token = localStorage.getItem(key);
    if (!token) throw new Error('aucun jeton stocké');
    const [header, payload, signature] = token.split('.');
    const middle = Math.floor(signature.length / 2);
    const replacement = signature[middle] === 'A' ? 'B' : 'A';
    const altered = signature.slice(0, middle) + replacement + signature.slice(middle + 1);
    localStorage.setItem(key, [header, payload, altered].join('.'));
  }, TOKEN_KEY);
}

/** Dépose un jeton bien formé mais expiré depuis une heure (signature factice). */
export async function storeExpiredToken(page: Page): Promise<void> {
  await page.evaluate((key) => {
    const encode = (value: object) =>
      btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    const exp = Math.floor(Date.now() / 1000) - 3600;
    const token = [encode({ alg: 'RS256', typ: 'JWT' }), encode({ exp }), 'signature'].join('.');
    localStorage.setItem(key, token);
  }, TOKEN_KEY);
}

/** Dépose une chaîne qui n'est pas un JWT décodable. */
export async function storeUnreadableToken(page: Page): Promise<void> {
  await page.evaluate((key) => localStorage.setItem(key, 'pas-un-jeton'), TOKEN_KEY);
}
