import { expect, test } from '@playwright/test';

import {
  apiAddExistingMember,
  apiBoards,
  apiCreateBoard,
  apiTrashBoard,
  bearer,
  boardBackend,
  boardTile,
  createBoardAccount,
  expectT1Upstream,
  favoritesGrid,
  ROUTING_MARKER,
  suffix,
  waitApi,
} from './support/boards';
import { storeSession } from './support/session';

// Accueil /boards (boards-members.md §3, phase-3-board-service.md §7.2), tranche T1.

test.describe('Accueil des tableaux — /boards', () => {
  test(
    'SC-BOARD-002 — accueil : état vide, puis mes tableaux seulement (ni ceux des autres ni la corbeille), rôles et compteurs exacts',
    { tag: '@board' },
    async ({ page, request }) => {
      const alice = await createBoardAccount(request, 'SC-BOARD-002', 'Alice');
      const carla = await createBoardAccount(request, 'SC-BOARD-002', 'Carla');

      // État vide : aucun tableau, message d'accueil.
      await storeSession(page, alice);
      const emptyHome = waitApi(page, 'GET', '/api/boards');
      await page.goto('/boards');
      const empty = await emptyHome;
      expect(empty.status()).toBe(200);
      expectT1Upstream(empty);
      expect(await empty.json()).toEqual([]);
      await expect(page.getByRole('heading', { name: 'Bienvenue sur Taskora !' })).toBeVisible();

      const s = suffix();
      const a = await apiCreateBoard(request, alice, { title: `E2E A ${s}` });
      const b = await apiCreateBoard(request, carla, { title: `E2E B ${s}` });
      await apiAddExistingMember(request, carla, b.id, alice);
      const c = await apiCreateBoard(request, carla, { title: `E2E C ${s}` });
      const d = await apiCreateBoard(request, alice, { title: `E2E D ${s}` });
      await apiTrashBoard(request, alice, d.id);

      const home = waitApi(page, 'GET', '/api/boards');
      await page.reload();
      const response = await home;
      expect(response.status()).toBe(200);
      expectT1Upstream(response);
      const boards = (await response.json()) as Awaited<ReturnType<typeof apiBoards>>;
      // A et B seulement. Ordre created_at DESC (ordre de travail §1.3 point 9) ; à la même
      // seconde, Symfony ne fixe pas l'ordre (ORDER BY created_at seul) : ordre vérifié hors égalité.
      expect(boards.map((x) => x.id).sort(), 'A et B seulement').toEqual([a.id, b.id].sort());
      const gotA = boards.find((x) => x.id === a.id)!;
      const gotB = boards.find((x) => x.id === b.id)!;
      if (gotA.createdAt !== gotB.createdAt) {
        expect(
          boards.map((x) => x.id),
          'B, créé après A, en premier',
        ).toEqual([b.id, a.id]);
      }
      expect(gotA).toMatchObject({ cardCount: 0, favorite: false, listCount: 4, role: 'owner' });
      expect(gotB).toMatchObject({ cardCount: 0, favorite: false, listCount: 4, role: 'member' });
      // Membres : contrat « triés par id d'adhésion » ; Symfony ne fixe pas cet ordre sur l'accueil
      // (collection Doctrine sans OrderBy) : comparaison sans ordre ici, écart signalé à BackAli.
      expect(gotA.members.map((m) => [m.id, m.role])).toEqual([[alice.user.id, 'owner']]);
      expect(gotB.members.map((m) => [m.id, m.role]).sort()).toEqual(
        [
          [carla.user.id, 'owner'],
          [alice.user.id, 'member'],
        ].sort(),
      );

      await expect(page.getByRole('heading', { name: 'Bienvenue sur Taskora !' })).toHaveCount(0);
      await expect(boardTile(page, a.title)).toBeVisible();
      await expect(boardTile(page, a.title)).toContainText('Propriétaire');
      await expect(boardTile(page, a.title)).toContainText('4 listes · 0 cartes');
      await expect(boardTile(page, b.title)).toContainText('Membre');
      await expect(boardTile(page, c.title)).toHaveCount(0);
      await expect(boardTile(page, d.title)).toHaveCount(0);
      await expect(page.locator('.stat').filter({ hasText: 'Tableaux' })).toContainText('2');
      await expect(page.locator('.stat').filter({ hasText: 'Collaborateurs' })).toContainText('2');
    },
  );

  test(
    'SC-BOARD-019 — recherche locale : titre ou description, insensible à la casse, favoris compris',
    { tag: '@board' },
    async ({ page, request }) => {
      const alice = await createBoardAccount(request, 'SC-BOARD-019', 'Alice');
      const s = suffix();
      const sprint = await apiCreateBoard(request, alice, { title: `E2E Sprint 12 ${s}` });
      const roadmap = await apiCreateBoard(request, alice, {
        description: 'Prévoir le prochain SPRINT',
        title: `E2E Roadmap ${s}`,
      });
      const budget = await apiCreateBoard(request, alice, { title: `E2E Budget ${s}` });
      const fav = await request.post(`/api/boards/${roadmap.id}/favorite`, {
        headers: bearer(alice),
      });
      expect(fav.status()).toBe(200);

      await storeSession(page, alice);
      await page.goto('/boards');
      await expect(boardTile(page, budget.title)).toBeVisible();

      await page.getByPlaceholder('Rechercher un tableau…').fill('spr');
      await expect(boardTile(page, sprint.title)).toBeVisible();
      // Roadmap (favori, « sprint » dans sa description) : dans Favoris et dans Tous les tableaux.
      await expect(favoritesGrid(page).locator('a.tile')).toHaveCount(1);
      await expect(favoritesGrid(page)).toContainText(roadmap.title);
      await expect(boardTile(page, roadmap.title)).toHaveCount(2);
      await expect(boardTile(page, budget.title)).toHaveCount(0);

      await page.getByPlaceholder('Rechercher un tableau…').fill('');
      await expect(boardTile(page, budget.title)).toBeVisible();
      await expect(boardTile(page, sprint.title)).toBeVisible();
    },
  );

  test(
    "SC-BOARD-008 — favori : l'étoile de l'accueil bascule le favori de l'appelant seulement (section Favoris, persistance)",
    { tag: '@board' },
    async ({ page, request }) => {
      const alice = await createBoardAccount(request, 'SC-BOARD-008', 'Alice');
      const bob = await createBoardAccount(request, 'SC-BOARD-008', 'Bob');
      const board = await apiCreateBoard(request, alice, { title: `E2E Favori ${suffix()}` });
      await apiAddExistingMember(request, alice, board.id, bob);

      await storeSession(page, alice);
      await page.goto('/boards');
      await expect(page.getByRole('heading', { name: 'Favoris' })).toHaveCount(0);

      const on = waitApi(page, 'POST', `/api/boards/${board.id}/favorite`);
      await boardTile(page, board.title)
        .getByRole('button', { name: 'Ajouter aux favoris' })
        .click();
      const onResponse = await on;
      expect(onResponse.status()).toBe(200);
      expectT1Upstream(onResponse);
      expect(await onResponse.json()).toEqual({ favorite: true });
      await expect(favoritesGrid(page)).toContainText(board.title);
      await expect(page, "le clic sur l'étoile n'ouvre pas le tableau").toHaveURL(/\/boards$/);

      await page.reload();
      await expect(favoritesGrid(page)).toContainText(board.title);
      const bobView = (await apiBoards(request, bob)).find((x) => x.id === board.id);
      expect(bobView?.favorite, 'favori de Bob inchangé').toBe(false);

      const off = waitApi(page, 'POST', `/api/boards/${board.id}/favorite`);
      await favoritesGrid(page).getByRole('button', { name: 'Retirer des favoris' }).click();
      const offResponse = await off;
      expect(await offResponse.json()).toEqual({ favorite: false });
      await expect(page.getByRole('heading', { name: 'Favoris' })).toHaveCount(0);
      const aliceView = (await apiBoards(request, alice)).find((x) => x.id === board.id);
      expect(aliceView?.favorite).toBe(false);
    },
  );

  test(
    "SC-BOARD-022 — routage de la tranche T1 : routes T1 servies par l'amont attendu, routes voisines toujours par Symfony",
    { tag: '@board' },
    async ({ request }) => {
      const alice = await createBoardAccount(request, 'SC-BOARD-022', 'Alice');
      const board = await apiCreateBoard(request, alice, { title: `E2E Routage ${suffix()}` });
      const headers = bearer(alice);

      const t1 = [
        await request.get('/api/boards', { headers }),
        await request.get(`/api/boards/${board.id}`, { headers }),
        await request.post(`/api/boards/${board.id}/favorite`, { headers }),
        await request.get('/api/trash/boards', { headers }),
      ];
      for (const response of t1) {
        expect(response.status(), response.url()).toBe(200);
        expectT1Upstream(response);
      }

      // Routes hors T1 : jamais servies par board-service tant que T2… ne sont pas basculées.
      const others = [
        await request.get(`/api/boards/${board.id}/cards`, { headers }),
        await request.get(`/api/boards/${board.id}/archived-lists`, { headers }),
        await request.get('/api/notifications', { headers }),
        await request.get('/api/me', { headers }),
      ];
      for (const response of others) {
        expect(response.status(), response.url()).toBe(200);
        expect(response.headers()[ROUTING_MARKER], `${response.url()} hors T1`).toBeUndefined();
      }
      expect(['board', 'symfony']).toContain(boardBackend());
    },
  );
});
