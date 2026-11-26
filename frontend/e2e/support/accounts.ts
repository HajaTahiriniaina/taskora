import { APIRequestContext, APIResponse, expect } from '@playwright/test';

// Comptes de test créés par l'API (docs/architecture/frontend.md §4.2) : jamais de compte de
// démonstration ni de donnée partagée entre tests ; rien n'est lu dans .env.

export interface PublicUser {
  avatarColor: string;
  email: string;
  id: number;
  name: string;
}

export interface TestAccount {
  email: string;
  name: string;
  password: string;
  token: string;
  user: PublicUser;
}

/** Palette de identity.md §1 (couleur tirée à l'inscription). */
export const AVATAR_PALETTE = [
  '#6366f1',
  '#ec4899',
  '#14b8a6',
  '#f59e0b',
  '#8b5cf6',
  '#ef4444',
  '#0ea5e9',
  '#22c55e',
];

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';

function randomString(length: number): string {
  const bytes = new Uint8Array(length);
  globalThis.crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => ALPHABET[b % ALPHABET.length]).join('');
}

/** Mot de passe aléatoire propre à l'exécution (20 caractères). */
export function randomPassword(): string {
  return randomString(20);
}

/** E-mail unique sur le domaine réservé example.com (RFC 2606). */
export function uniqueEmail(scenario: string): string {
  const id = scenario.toLowerCase().replace(/[^a-z0-9]+/g, '-');
  return `e2e-${id}-${Date.now()}-${randomString(8).toLowerCase()}@example.com`;
}

/** Inscription par POST /api/auth/register ; échoue si l'API ne répond pas 201. */
export async function createAccount(
  request: APIRequestContext,
  scenario: string,
): Promise<TestAccount> {
  const email = uniqueEmail(scenario);
  const password = randomPassword();
  const name = `E2E ${scenario}`;
  const response = await request.post('/api/auth/register', { data: { email, name, password } });
  expect(response.status(), 'POST /api/auth/register').toBe(201);
  const body = (await response.json()) as { token: string; user: PublicUser };
  return { email, name, password, token: body.token, user: body.user };
}

/** POST /api/auth/login par l'API (sans passer par l'UI). */
export function apiLogin(
  request: APIRequestContext,
  email: string,
  password: string,
): Promise<APIResponse> {
  return request.post('/api/auth/login', { data: { email, password } });
}

/** GET /api/me avec le jeton fourni. */
export function apiMe(request: APIRequestContext, token: string): Promise<APIResponse> {
  return request.get('/api/me', { headers: { Authorization: `Bearer ${token}` } });
}
