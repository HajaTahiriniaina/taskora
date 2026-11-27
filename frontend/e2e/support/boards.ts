import { APIRequestContext, APIResponse, expect, Page } from '@playwright/test';

import { PublicUser, randomPassword, TestAccount } from './accounts';

// e2e @board (phase 3, tranche T1) : données synthétiques @exemple.test créées par l'API dans
// chaque test ; aucune donnée partagée entre tests ; rien n'est lu dans .env.

/**
 * Amont effectif de la tranche T1 (BOARD_T1_UPSTREAM) : `symfony` (api:80) ou `board`
 * (board:8080). La stack de test active BOARD_ROUTING_MARKER=on : board-service ajoute alors
 * l'en-tête X-Taskora-Backend: board à ses réponses (SC-BOARD-022).
 */
export function boardBackend(): 'board' | 'symfony' {
  const value = process.env['E2E_BOARD_BACKEND'];
  if (value !== 'board' && value !== 'symfony') {
    throw new Error('E2E_BOARD_BACKEND doit valoir « symfony » ou « board » pour les e2e @board.');
  }
  return value;
}

export const ROUTING_MARKER = 'x-taskora-backend';

/** La réponse d'une route T1 vient de l'amont attendu (marqueur présent si et seulement si board). */
export function expectT1Upstream(response: APIResponse | { headers(): Record<string, string> }) {
  const marker = response.headers()[ROUTING_MARKER];
  if (boardBackend() === 'board') {
    expect(marker, 'route T1 servie par board-service').toBe('board');
  } else {
    expect(marker, 'route T1 servie par Symfony (aucun marqueur)').toBeUndefined();
  }
}

export interface Member extends PublicUser {
  joinedAt: string;
  role: 'admin' | 'member' | 'owner';
}

export interface BoardSummary {
  background: string;
  cardCount: number;
  createdAt: string;
  description: null | string;
  favorite: boolean;
  id: number;
  listCount: number;
  members: Member[];
  role: 'admin' | 'member' | 'owner' | null;
  showCardNumbers: boolean;
  title: string;
}

export interface BoardShell extends BoardSummary {
  customFields: unknown[];
  invitations: { createdAt: string; email: string; id: number }[];
  labels: { color: string; id: number; name: string }[];
  lists: {
    archivedAt: null | string;
    cardCount: number;
    color: null | string;
    id: number;
    position: number;
    title: string;
  }[];
  trelloFiles: number;
}

export interface AppNotification {
  boardId: null | number;
  message: string;
  type: string;
}

/** Listes et étiquettes créées par défaut (boards-members.md §1, SC-BOARD-001). */
export const DEFAULT_LISTS = ['À faire', 'En cours', 'En revue', 'Terminé'];
export const DEFAULT_LABELS = [
  { color: '#ef4444', name: 'Urgent' },
  { color: '#f97316', name: 'Bug' },
  { color: '#6366f1', name: 'Feature' },
  { color: '#ec4899', name: 'Design' },
  { color: '#14b8a6', name: 'Backend' },
  { color: '#eab308', name: 'Idée' },
];

/** Suffixe aléatoire (minuscules et chiffres) propre au test. */
export function suffix(): string {
  return randomPassword()
    .toLowerCase()
    .replace(/[^a-z0-9]/g, '')
    .slice(0, 8);
}

/** E-mail unique sur le domaine réservé exemple.test (données synthétiques). */
export function boardEmail(scenario: string): string {
  const id = scenario.toLowerCase().replace(/[^a-z0-9]+/g, '-');
  return `e2e-${id}-${Date.now()}-${suffix()}@exemple.test`;
}

/** Compte de test créé par POST /api/auth/register (nom unique, e-mail @exemple.test). */
export async function createBoardAccount(
  request: APIRequestContext,
  scenario: string,
  firstName: string,
): Promise<TestAccount> {
  const email = boardEmail(`${scenario}-${firstName}`);
  const password = randomPassword();
  const name = `${firstName} E2E ${suffix()}`;
  const response = await request.post('/api/auth/register', { data: { email, name, password } });
  expect(response.status(), 'POST /api/auth/register').toBe(201);
  const body = (await response.json()) as { token: string; user: PublicUser };
  return { email, name, password, token: body.token, user: body.user };
}

export function bearer(account: TestAccount): Record<string, string> {
  return { Authorization: `Bearer ${account.token}` };
}

/** POST /api/boards par l'API ; échoue si la réponse n'est pas 201. */
export async function apiCreateBoard(
  request: APIRequestContext,
  account: TestAccount,
  data: { background?: string; description?: string; title: string; withDefaults?: boolean },
): Promise<BoardShell> {
  const response = await request.post('/api/boards', { data, headers: bearer(account) });
  expect(response.status(), 'POST /api/boards').toBe(201);
  expectT1Upstream(response);
  return (await response.json()) as BoardShell;
}

/** POST /api/boards/{id}/members (ajout d'un compte existant ou invitation). */
export function apiAddMember(
  request: APIRequestContext,
  manager: TestAccount,
  boardId: number,
  email: string,
  role: 'admin' | 'member' = 'member',
): Promise<APIResponse> {
  return request.post(`/api/boards/${boardId}/members`, {
    data: { email, role },
    headers: bearer(manager),
  });
}

/** Ajoute un compte existant au tableau et vérifie le 201. */
export async function apiAddExistingMember(
  request: APIRequestContext,
  manager: TestAccount,
  boardId: number,
  member: TestAccount,
  role: 'admin' | 'member' = 'member',
): Promise<void> {
  const response = await apiAddMember(request, manager, boardId, member.email, role);
  expect(response.status(), `POST /api/boards/${boardId}/members`).toBe(201);
}

/** GET /api/boards (accueil) de ce compte. */
export async function apiBoards(
  request: APIRequestContext,
  account: TestAccount,
): Promise<BoardSummary[]> {
  const response = await request.get('/api/boards', { headers: bearer(account) });
  expect(response.status(), 'GET /api/boards').toBe(200);
  expectT1Upstream(response);
  return (await response.json()) as BoardSummary[];
}

/** GET /api/boards/{id} (coquille), réponse brute. */
export function apiBoard(
  request: APIRequestContext,
  account: TestAccount,
  boardId: number | string,
): Promise<APIResponse> {
  return request.get(`/api/boards/${boardId}`, { headers: bearer(account) });
}

/** DELETE /api/boards/{id} par le propriétaire (mise à la corbeille, 204). */
export async function apiTrashBoard(
  request: APIRequestContext,
  owner: TestAccount,
  boardId: number,
): Promise<void> {
  const response = await request.delete(`/api/boards/${boardId}`, { headers: bearer(owner) });
  expect(response.status(), `DELETE /api/boards/${boardId}`).toBe(204);
}

/** GET /api/notifications (Symfony, toujours) : toutes les notifications de ce compte. */
export async function apiAllNotifications(
  request: APIRequestContext,
  account: TestAccount,
): Promise<AppNotification[]> {
  const response = await request.get('/api/notifications', { headers: bearer(account) });
  expect(response.status(), 'GET /api/notifications').toBe(200);
  return ((await response.json()) as { items: AppNotification[] }).items;
}

/** GET /api/notifications (Symfony, toujours) : notifications de ce compte pour ce tableau. */
export async function apiNotifications(
  request: APIRequestContext,
  account: TestAccount,
  boardId: number,
): Promise<AppNotification[]> {
  const response = await request.get('/api/notifications', { headers: bearer(account) });
  expect(response.status(), 'GET /api/notifications').toBe(200);
  const body = (await response.json()) as { items: AppNotification[] };
  return body.items.filter((n) => n.boardId === boardId);
}

/**
 * Notifications livrées (board-service : outbox asynchrone vers Symfony) : attend qu'au moins
 * `count` notifications du tableau soient arrivées, puis les rend (types et messages).
 */
export async function waitNotifications(
  request: APIRequestContext,
  account: TestAccount,
  boardId: number,
  count: number,
): Promise<{ message: string; type: string }[]> {
  let items: AppNotification[] = [];
  await expect
    .poll(
      async () => {
        items = await apiNotifications(request, account, boardId);
        return items.length;
      },
      { message: `${count} notification(s) livrée(s)`, timeout: 30_000 },
    )
    .toBeGreaterThanOrEqual(count);
  return items.map((n) => ({ message: n.message, type: n.type }));
}

/** Attend la réponse d'une requête de l'application (méthode et chemin exacts, sans requête). */
export function waitApi(page: Page, method: string, path: RegExp | string) {
  return page.waitForResponse((r) => {
    if (r.request().method() !== method) return false;
    const pathname = new URL(r.url()).pathname;
    return typeof path === 'string' ? pathname === path : path.test(pathname);
  });
}

/** Toast SweetAlert2 (role="alert") portant exactement ce texte. */
export async function expectToast(page: Page, text: string): Promise<void> {
  const toast = page.getByRole('alert').filter({ hasText: text });
  // Les toasts sont affichés l'un après l'autre (file d'attente de ToastService).
  await expect(toast).toBeVisible({ timeout: 15_000 });
  // Texte exact du message (le toast contient aussi le bouton de fermeture).
  await expect(toast.locator('.tk-toast-text')).toHaveText(text);
}

/** Ferme le toast affiché, pour que le suivant (même texte) soit bien un nouveau toast. */
export async function dismissToast(page: Page, text: string): Promise<void> {
  const toast = page.getByRole('alert').filter({ hasText: text });
  await toast.getByRole('button', { name: 'Fermer la notification' }).click();
  await expect(toast).toHaveCount(0);
}

/** Tuile d'un tableau sur l'accueil (lien vers /boards/{id}). */
export function boardTile(page: Page, title: string) {
  return page.locator('a.tile').filter({ has: page.getByRole('heading', { name: title }) });
}

/** Grille de la section « Favoris » de l'accueil. */
export function favoritesGrid(page: Page) {
  return page
    .locator('h2.section', { hasText: 'Favoris' })
    .locator('xpath=following-sibling::div[1]');
}

/** Tiroir des paramètres du tableau. */
export function settingsDrawer(page: Page) {
  return page.getByRole('dialog', { name: 'Paramètres du tableau' });
}

/** Ligne d'un membre (ou d'une invitation) dans l'onglet Membres. */
export function personRow(page: Page, email: string) {
  return settingsDrawer(page).getByRole('listitem').filter({ hasText: email });
}

/** Boîte de confirmation SweetAlert2. */
export function confirmDialog(page: Page, title: string) {
  return page.locator('.tk-swal').filter({ hasText: title });
}
