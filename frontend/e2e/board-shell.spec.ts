import { expect, test } from '@playwright/test';

import {
  apiAddExistingMember,
  apiAddMember,
  apiBoard,
  apiBoards,
  apiCreateBoard,
  boardEmail,
  BoardShell,
  boardTile,
  confirmDialog,
  createBoardAccount,
  DEFAULT_LABELS,
  DEFAULT_LISTS,
  expectT1Upstream,
  expectToast,
  settingsDrawer,
  suffix,
  waitApi,
} from './support/boards';
import { storeSession } from './support/session';

// Création, coquille et paramètres d'un tableau (boards-members.md, pages /boards et /boards/{id}).

const SUMMARY_KEYS = [
  'background',
  'cardCount',
  'createdAt',
  'description',
  'favorite',
  'id',
  'listCount',
  'members',
  'role',
  'showCardNumbers',
  'title',
];
const SHELL_KEYS = [
  ...SUMMARY_KEYS,
  'customFields',
  'invitations',
  'labels',
  'lists',
  'trelloFiles',
].sort();

test.describe('Création et coquille du tableau', () => {
  test(
    "SC-BOARD-001 — création depuis l'accueil : 201, propriétaire, 4 listes et 6 étiquettes par défaut, ouverture de /boards/{id}",
    { tag: '@board' },
    async ({ page, request }) => {
      const alice = await createBoardAccount(request, 'SC-BOARD-001', 'Alice');
      const title = `E2E Sprint 12 ${suffix()}`;

      await storeSession(page, alice);
      await page.goto('/boards');
      await page.getByRole('button', { name: 'Nouveau tableau' }).click();
      await page.getByRole('button', { exact: true, name: 'ocean' }).click();
      await page.getByLabel('Titre du tableau').fill(title);
      const created = waitApi(page, 'POST', '/api/boards');
      await page.getByRole('button', { name: 'Créer le tableau' }).click();
      const response = await created;

      expect(response.status()).toBe(201);
      expectT1Upstream(response);
      const board = (await response.json()) as BoardShell;
      expect(Object.keys(board).sort()).toEqual(SHELL_KEYS);
      expect(board).toMatchObject({
        background: 'ocean',
        cardCount: 0,
        // Création : description envoyée vide par le front, stockée telle quelle (`?? null`).
        description: '',
        favorite: false,
        listCount: 4,
        role: 'owner',
        showCardNumbers: true,
        title,
        trelloFiles: 0,
      });
      expect(board.lists.map((l) => [l.title, l.position, l.cardCount])).toEqual(
        DEFAULT_LISTS.map((t, i) => [t, i, 0]),
      );
      expect(board.labels.map((l) => ({ color: l.color, name: l.name }))).toEqual(DEFAULT_LABELS);
      expect(board.members.map((m) => [m.id, m.role])).toEqual([[alice.user.id, 'owner']]);

      await expect(page).toHaveURL(new RegExp(`/boards/${board.id}$`));
      await expectToast(page, 'Tableau créé 🎉');
      await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible();
      await expect(page.getByRole('heading', { level: 2 })).toHaveText(DEFAULT_LISTS);

      // Retour à l'accueil : le tableau y figure, rôle Propriétaire.
      await page.getByRole('link', { name: 'Retour aux tableaux' }).click();
      await expect(boardTile(page, title)).toContainText('Propriétaire');
    },
  );

  test(
    'SC-BOARD-003 — coquille du tableau : schéma du contrat, listes ordonnées sans cartes, membres et invitations',
    { tag: '@board' },
    async ({ page, request }) => {
      const alice = await createBoardAccount(request, 'SC-BOARD-003', 'Alice');
      const bob = await createBoardAccount(request, 'SC-BOARD-003', 'Bob');
      const board = await apiCreateBoard(request, alice, {
        description: 'Coquille de référence',
        title: `E2E Coquille ${suffix()}`,
      });
      await apiAddExistingMember(request, alice, board.id, bob, 'admin');
      const invited = boardEmail('SC-BOARD-003-invite');
      expect((await apiAddMember(request, alice, board.id, invited)).status()).toBe(202);

      await storeSession(page, bob);
      const shell = waitApi(page, 'GET', `/api/boards/${board.id}`);
      await page.goto(`/boards/${board.id}`);
      const response = await shell;

      expect(response.status()).toBe(200);
      expectT1Upstream(response);
      const body = (await response.json()) as BoardShell;
      expect(Object.keys(body).sort()).toEqual(SHELL_KEYS);
      expect(body).toMatchObject({
        description: 'Coquille de référence',
        id: board.id,
        listCount: 4,
        role: 'admin',
        title: board.title,
      });
      for (const list of body.lists) {
        expect(Object.keys(list).sort(), 'liste sans cartes').toEqual([
          'archivedAt',
          'cardCount',
          'color',
          'id',
          'position',
          'title',
        ]);
      }
      expect(body.lists.map((l) => l.title)).toEqual(DEFAULT_LISTS);
      expect(body.members.map((m) => [m.id, m.role])).toEqual([
        [alice.user.id, 'owner'],
        [bob.user.id, 'admin'],
      ]);
      for (const member of body.members) {
        expect(Object.keys(member).sort()).toEqual([
          'avatarColor',
          'email',
          'id',
          'joinedAt',
          'name',
          'role',
        ]);
      }
      expect(body.invitations.map((i) => i.email)).toEqual([invited]);

      await expect(page.getByRole('heading', { level: 1, name: board.title })).toBeVisible();
      await expect(page.getByRole('heading', { level: 2 })).toHaveText(DEFAULT_LISTS);
    },
  );

  test(
    'SC-BOARD-005 — onglet Général : titre et description enregistrés (description vide → null), persistés après rechargement',
    { tag: '@board' },
    async ({ page, request }) => {
      const alice = await createBoardAccount(request, 'SC-BOARD-005', 'Alice');
      const board = await apiCreateBoard(request, alice, {
        description: 'À effacer',
        title: `E2E Sprint 12 ${suffix()}`,
      });
      const renamed = board.title.replace('Sprint 12', 'Sprint 13');

      await storeSession(page, alice);
      await page.goto(`/boards/${board.id}`);
      await page.getByRole('button', { name: 'Paramètres du tableau' }).click();
      await settingsDrawer(page).getByRole('button', { name: 'Général' }).click();
      const form = settingsDrawer(page).locator('form').filter({ hasText: 'Titre' });
      await form.locator('input[name="t"]').fill(renamed);
      await form.locator('textarea[name="d"]').fill('');
      const patched = waitApi(page, 'PATCH', `/api/boards/${board.id}`);
      await form.getByRole('button', { name: 'Enregistrer' }).click();
      const response = await patched;

      expect(response.status()).toBe(200);
      expectT1Upstream(response);
      expect(await response.json()).toMatchObject({ description: null, title: renamed });
      await expectToast(page, 'Tableau mis à jour');

      await page.reload();
      await expect(page.getByRole('heading', { level: 1, name: renamed })).toBeVisible();
      const reread = (await (await apiBoard(request, alice, board.id)).json()) as BoardShell;
      expect(reread).toMatchObject({ description: null, title: renamed });
    },
  );

  test(
    "SC-BOARD-007 — suppression : zone de danger réservée au propriétaire, confirmation, tableau retiré de l'accueil de tous",
    { tag: '@board' },
    async ({ page, request }) => {
      const alice = await createBoardAccount(request, 'SC-BOARD-007', 'Alice');
      const bob = await createBoardAccount(request, 'SC-BOARD-007', 'Bob');
      const board = await apiCreateBoard(request, alice, { title: `E2E Corbeille ${suffix()}` });
      await apiAddExistingMember(request, alice, board.id, bob, 'admin');

      // Bob (admin) : pas de zone de danger ; l'API refuse.
      await storeSession(page, bob);
      await page.goto(`/boards/${board.id}`);
      await page.getByRole('button', { name: 'Paramètres du tableau' }).click();
      await settingsDrawer(page).getByRole('button', { name: 'Général' }).click();
      await expect(settingsDrawer(page).getByRole('button', { name: 'Enregistrer' })).toBeVisible();
      await expect(
        settingsDrawer(page).getByRole('button', { name: 'Supprimer le tableau' }),
      ).toHaveCount(0);
      const refused = await request.delete(`/api/boards/${board.id}`, {
        headers: { Authorization: `Bearer ${bob.token}` },
      });
      expect(refused.status()).toBe(403);
      expectT1Upstream(refused);
      expect(await refused.json()).toEqual({
        error: 'Seul le propriétaire peut supprimer le tableau.',
      });

      // Alice (propriétaire) : confirmation puis disparition.
      await storeSession(page, alice);
      await page.goto(`/boards/${board.id}`);
      await page.getByRole('button', { name: 'Paramètres du tableau' }).click();
      await settingsDrawer(page).getByRole('button', { name: 'Général' }).click();
      await settingsDrawer(page).getByRole('button', { name: 'Supprimer le tableau' }).click();
      const dialog = confirmDialog(page, 'Mettre le tableau à la corbeille ?');
      await expect(dialog).toBeVisible();
      const deleted = waitApi(page, 'DELETE', `/api/boards/${board.id}`);
      await dialog.getByRole('button', { name: 'Mettre à la corbeille' }).click();
      const response = await deleted;

      expect(response.status()).toBe(204);
      expectT1Upstream(response);
      await expectToast(page, 'Tableau mis à la corbeille');
      await expect(page).toHaveURL(/\/boards$/);
      await expect(page.getByRole('heading', { name: 'Tous les tableaux' })).toBeVisible();
      await expect(boardTile(page, board.title)).toHaveCount(0);
      expect((await apiBoards(request, alice)).map((x) => x.id)).not.toContain(board.id);
      expect((await apiBoards(request, bob)).map((x) => x.id)).not.toContain(board.id);
    },
  );
});
