import { expect, test } from '@playwright/test';

import {
  apiAddExistingMember,
  apiBoard,
  apiBoards,
  apiCreateBoard,
  apiTrashBoard,
  bearer,
  boardTile,
  createBoardAccount,
  dismissToast,
  expectT1Upstream,
  expectToast,
  suffix,
  waitApi,
} from './support/boards';
import { storeSession } from './support/session';

// Corbeille des tableaux de l'accueil (archives-trash.md SC-TRASH-007, phase-3 SC-BOARD-034).

test.describe('Corbeille des tableaux — bouton « Corbeille » de l’accueil', () => {
  test(
    'SC-TRASH-007 / SC-BOARD-034 — corbeille du propriétaire (jours restants), invisible pour un admin, restauration pour tous les membres',
    { tag: '@board' },
    async ({ browser, page, request }) => {
      const alice = await createBoardAccount(request, 'SC-TRASH-007', 'Alice');
      const bob = await createBoardAccount(request, 'SC-TRASH-007', 'Bob');
      const board = await apiCreateBoard(request, alice, { title: `E2E Restaurable ${suffix()}` });
      await apiAddExistingMember(request, alice, board.id, bob, 'admin');
      await apiTrashBoard(request, alice, board.id);

      // Bob (admin) : corbeille vide, restauration refusée, tableau introuvable par ses routes.
      const bobContext = await browser.newContext();
      const bobPage = await bobContext.newPage();
      await storeSession(bobPage, bob);
      await bobPage.goto('/boards');
      const bobTrash = waitApi(bobPage, 'GET', '/api/trash/boards');
      await bobPage.getByRole('button', { name: 'Corbeille' }).click();
      const bobTrashResponse = await bobTrash;
      expect(bobTrashResponse.status()).toBe(200);
      expectT1Upstream(bobTrashResponse);
      expect(await bobTrashResponse.json()).toEqual([]);
      await expect(
        bobPage.getByRole('dialog', { name: 'Corbeille' }).getByText('La corbeille est vide.'),
      ).toBeVisible();
      const refused = await request.post(`/api/trash/boards/${board.id}/restore`, {
        headers: bearer(bob),
      });
      expect(refused.status()).toBe(403);
      expectT1Upstream(refused);
      const memberView = await apiBoard(request, bob, board.id);
      expect(memberView.status()).toBe(404);
      expect(await memberView.json()).toEqual({ error: 'Ce tableau est dans la corbeille.' });

      // Alice : le tableau est dans sa corbeille, 30 jours restants ; restauration.
      await storeSession(page, alice);
      await page.goto('/boards');
      await expect(boardTile(page, board.title)).toHaveCount(0);
      const aliceTrash = waitApi(page, 'GET', '/api/trash/boards');
      await page.getByRole('button', { name: 'Corbeille' }).click();
      const aliceTrashResponse = await aliceTrash;
      expect(aliceTrashResponse.status()).toBe(200);
      expectT1Upstream(aliceTrashResponse);
      const trashed = (await aliceTrashResponse.json()) as { id: number; title: string }[];
      expect(trashed.map((t) => t.id)).toEqual([board.id]);
      const dialog = page.getByRole('dialog', { name: 'Corbeille' });
      const row = dialog.locator('.trash-row').filter({ hasText: board.title });
      await expect(row).toContainText('30 j restants');

      const restored = waitApi(page, 'POST', `/api/trash/boards/${board.id}/restore`);
      await row.getByRole('button', { name: 'Restaurer' }).click();
      const restoreResponse = await restored;
      expect(restoreResponse.status()).toBe(200);
      expectT1Upstream(restoreResponse);
      expect(await restoreResponse.json()).toEqual({ id: board.id });
      await expectToast(page, `« ${board.title} » restauré`);
      await expect(row).toHaveCount(0);
      await dialog.getByRole('button', { exact: true, name: 'Fermer' }).click();
      await expect(boardTile(page, board.title)).toBeVisible();

      // Le tableau réapparaît pour Bob avec son contenu ; plus dans la corbeille → 404.
      const bobBoards = await apiBoards(request, bob);
      expect(bobBoards.find((b) => b.id === board.id)).toMatchObject({
        listCount: 4,
        role: 'admin',
      });
      await bobPage.reload();
      await expect(boardTile(bobPage, board.title)).toBeVisible();
      const again = await request.post(`/api/trash/boards/${board.id}/restore`, {
        headers: bearer(alice),
      });
      expect(again.status()).toBe(404);
      expect(await again.json()).toEqual({ error: 'Ce tableau n’est pas dans la corbeille.' });
      await bobContext.close();
    },
  );

  test(
    'SC-BOARD-004 — tableau dans la corbeille ouvert par un membre : toast « Ce tableau est dans la corbeille. » puis accueil',
    { tag: '@board' },
    async ({ page, request }) => {
      const alice = await createBoardAccount(request, 'SC-BOARD-004', 'Alice');
      const carla = await createBoardAccount(request, 'SC-BOARD-004', 'Carla');
      const board = await apiCreateBoard(request, alice, { title: `E2E Supprimé ${suffix()}` });
      await apiAddExistingMember(request, alice, board.id, carla);
      await apiTrashBoard(request, alice, board.id);

      for (const account of [carla, alice]) {
        await storeSession(page, account);
        const shell = waitApi(page, 'GET', `/api/boards/${board.id}`);
        await page.goto(`/boards/${board.id}`);
        const response = await shell;
        expect(response.status(), account.name).toBe(404);
        expectT1Upstream(response);
        expect(await response.json()).toEqual({ error: 'Ce tableau est dans la corbeille.' });
        await expectToast(page, 'Ce tableau est dans la corbeille.');
        await expect(page).toHaveURL(/\/boards$/);
        await dismissToast(page, 'Ce tableau est dans la corbeille.');
      }
    },
  );
});
