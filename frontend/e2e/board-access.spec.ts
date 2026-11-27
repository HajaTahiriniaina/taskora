import { expect, test } from '@playwright/test';

import {
  apiBoard,
  apiCreateBoard,
  apiTrashBoard,
  bearer,
  createBoardAccount,
  dismissToast,
  expectT1Upstream,
  expectToast,
  suffix,
  waitApi,
} from './support/boards';
import { storeSession } from './support/session';

// Non-membre : 404 indiscernable d'un tableau inexistant (U8-a, phase-3 §5.3, SC-BOARD-006/021).

const NOT_FOUND = { error: 'Tableau introuvable.' };

test.describe('Accès d’un non-membre', () => {
  test(
    'SC-BOARD-021 / SC-BOARD-006 — non-membre sur /boards/{id} : toast « Tableau introuvable. », retour à l’accueil, réponse identique à un tableau inexistant',
    { tag: '@board' },
    async ({ page, request }) => {
      const alice = await createBoardAccount(request, 'SC-BOARD-021', 'Alice');
      const bob = await createBoardAccount(request, 'SC-BOARD-021', 'Bob');
      const open = await apiCreateBoard(request, alice, { title: `E2E Privé ${suffix()}` });
      const trashed = await apiCreateBoard(request, alice, {
        title: `E2E Privé supprimé ${suffix()}`,
      });
      await apiTrashBoard(request, alice, trashed.id);
      const missing = 2_000_000_000;

      // Référence : tableau inexistant.
      const reference = await apiBoard(request, bob, missing);
      expect(reference.status()).toBe(404);
      expectT1Upstream(reference);
      const referenceBody = await reference.text();
      expect(JSON.parse(referenceBody)).toEqual(NOT_FOUND);

      await storeSession(page, bob);
      for (const id of [open.id, trashed.id, missing]) {
        const shell = waitApi(page, 'GET', `/api/boards/${id}`);
        await page.goto(`/boards/${id}`);
        const response = await shell;
        expect(response.status(), `tableau ${id}`).toBe(404);
        expectT1Upstream(response);
        expect(await response.text(), 'corps identique à un tableau inexistant').toBe(
          referenceBody,
        );
        expect(response.headers()['content-type']).toBe(reference.headers()['content-type']);
        await expectToast(page, 'Tableau introuvable.');
        await expect(page).toHaveURL(/\/boards$/);
        await dismissToast(page, 'Tableau introuvable.');
      }

      // Routes T1 du tableau d'Alice : même 404, aucune écriture (U8-a, avant l'analyse du corps).
      const headers = bearer(bob);
      const attempts = [
        await request.patch(`/api/boards/${open.id}`, { data: { title: '' }, headers }),
        await request.delete(`/api/boards/${open.id}`, { headers }),
        await request.post(`/api/boards/${open.id}/favorite`, { headers }),
        await request.post(`/api/boards/${open.id}/members`, { data: { email: 'x' }, headers }),
      ];
      for (const response of attempts) {
        expect(response.status(), response.url()).toBe(404);
        expectT1Upstream(response);
        expect(await response.json()).toEqual(NOT_FOUND);
      }
      const unchanged = await apiBoard(request, alice, open.id);
      expect(await unchanged.json()).toMatchObject({ favorite: false, title: open.title });
    },
  );
});
