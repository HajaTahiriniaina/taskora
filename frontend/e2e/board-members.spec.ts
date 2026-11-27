import { expect, Page, test } from '@playwright/test';

import { TestAccount } from './support/accounts';
import {
  apiAddExistingMember,
  apiAddMember,
  apiAllNotifications,
  apiBoard,
  apiBoards,
  apiCreateBoard,
  apiNotifications,
  boardEmail,
  BoardShell,
  boardTile,
  confirmDialog,
  createBoardAccount,
  expectT1Upstream,
  expectToast,
  personRow,
  settingsDrawer,
  suffix,
  waitApi,
  waitNotifications,
} from './support/boards';
import { storeSession } from './support/session';

// Onglet Membres du tableau (boards-members.md SC-BOARD-009 à 016, phase-3 SC-BOARD-032/033).

async function openMembers(page: Page, account: TestAccount, boardId: number) {
  await storeSession(page, account);
  await page.goto(`/boards/${boardId}`);
  await page.getByRole('button', { name: 'Partager' }).click();
  await expect(settingsDrawer(page)).toBeVisible();
}

function inviteInput(page: Page) {
  return settingsDrawer(page).getByPlaceholder('collegue@entreprise.com');
}

function inviteRole(page: Page) {
  return settingsDrawer(page).locator('form.invite select');
}

test.describe('Membres du tableau', () => {
  test(
    'SC-BOARD-009 — ajout d’un compte existant (e-mail en autre casse, rôle Admin) : membre listé, notifications board_invite et member_joined',
    { tag: '@board' },
    async ({ page, request }) => {
      const alice = await createBoardAccount(request, 'SC-BOARD-009', 'Alice');
      const bob = await createBoardAccount(request, 'SC-BOARD-009', 'Bob');
      const carla = await createBoardAccount(request, 'SC-BOARD-009', 'Carla');
      const board = await apiCreateBoard(request, alice, { title: `E2E Membres ${suffix()}` });
      await apiAddExistingMember(request, alice, board.id, carla);

      await openMembers(page, alice, board.id);
      await inviteInput(page).fill(` ${bob.email.toUpperCase()} `);
      await inviteRole(page).selectOption('admin');
      const added = waitApi(page, 'POST', `/api/boards/${board.id}/members`);
      await settingsDrawer(page).getByRole('button', { name: 'Inviter' }).click();
      const response = await added;

      expect(response.status()).toBe(201);
      expectT1Upstream(response);
      const body = (await response.json()) as {
        member: { id: number; role: string };
        status: string;
      };
      expect(body.status).toBe('added');
      expect(body.member).toMatchObject({ id: bob.user.id, role: 'admin' });
      await expectToast(page, `${bob.name} a été ajouté au tableau`);
      await expect(personRow(page, bob.email)).toBeVisible();
      await expect(personRow(page, bob.email).getByRole('combobox')).toHaveValue('admin');

      // Notifications (outbox de board-service ou Symfony) : Bob board_invite, Carla member_joined.
      expect(await waitNotifications(request, bob, board.id, 1)).toEqual([
        {
          message: `${alice.name} vous a ajouté au tableau « ${board.title} »`,
          type: 'board_invite',
        },
      ]);
      // Carla : son propre board_invite (ajout initial) puis member_joined pour Bob.
      const carlaNotifications = await waitNotifications(request, carla, board.id, 2);
      expect(carlaNotifications.filter((n) => n.type !== 'board_invite')).toEqual([
        { message: `${bob.name} a rejoint le tableau « ${board.title} »`, type: 'member_joined' },
      ]);
      expect(await apiNotifications(request, alice, board.id), 'jamais l’acteur').toEqual([]);

      // Déjà membre : 409, message de l'API en toast.
      await inviteInput(page).fill(bob.email);
      const again = waitApi(page, 'POST', `/api/boards/${board.id}/members`);
      await settingsDrawer(page).getByRole('button', { name: 'Inviter' }).click();
      expect((await again).status()).toBe(409);
      await expectToast(page, 'Cet utilisateur est déjà membre du tableau.');
    },
  );

  test(
    'SC-BOARD-011 / SC-BOARD-012 — invitation d’un e-mail sans compte (202, en attente), doublon 409, annulation',
    { tag: '@board' },
    async ({ page, request }) => {
      const alice = await createBoardAccount(request, 'SC-BOARD-011', 'Alice');
      const board = await apiCreateBoard(request, alice, { title: `E2E Invitations ${suffix()}` });
      const invited = boardEmail('SC-BOARD-011-nouveau');

      await openMembers(page, alice, board.id);
      await inviteInput(page).fill(invited);
      const posted = waitApi(page, 'POST', `/api/boards/${board.id}/members`);
      await settingsDrawer(page).getByRole('button', { name: 'Inviter' }).click();
      const response = await posted;

      expect(response.status()).toBe(202);
      expectT1Upstream(response);
      const message = `Invitation enregistrée : ${invited} rejoindra le tableau à son inscription.`;
      expect(await response.json()).toEqual({ message, status: 'invited' });
      await expectToast(page, message);
      await expect(
        settingsDrawer(page).getByRole('heading', { name: /Invitations en attente/ }),
      ).toBeVisible();
      await expect(personRow(page, invited)).toBeVisible();
      const shell = (await (await apiBoard(request, alice, board.id)).json()) as BoardShell;
      expect(shell.invitations.map((i) => i.email)).toEqual([invited]);

      // Même adresse : 409.
      await inviteInput(page).fill(invited);
      const duplicate = waitApi(page, 'POST', `/api/boards/${board.id}/members`);
      await settingsDrawer(page).getByRole('button', { name: 'Inviter' }).click();
      expect((await duplicate).status()).toBe(409);
      await expectToast(page, 'Une invitation est déjà en attente pour cette adresse.');

      // Annulation (après rechargement : identifiant réel de l'invitation).
      await page.reload();
      await page.getByRole('button', { name: 'Partager' }).click();
      const invitationId = shell.invitations[0].id;
      const cancelled = waitApi(
        page,
        'DELETE',
        `/api/boards/${board.id}/invitations/${invitationId}`,
      );
      await personRow(page, invited).getByRole('button', { name: "Annuler l'invitation" }).click();
      const cancelResponse = await cancelled;
      expect(cancelResponse.status()).toBe(204);
      expectT1Upstream(cancelResponse);
      await expect(personRow(page, invited)).toHaveCount(0);
      const after = (await (await apiBoard(request, alice, board.id)).json()) as BoardShell;
      expect(after.invitations).toEqual([]);
    },
  );

  test(
    'SC-BOARD-014 / SC-BOARD-032 — changement de rôle par un gestionnaire ; rôle du propriétaire non modifiable dans l’interface',
    { tag: '@board' },
    async ({ page, request }) => {
      const alice = await createBoardAccount(request, 'SC-BOARD-014', 'Alice');
      const carla = await createBoardAccount(request, 'SC-BOARD-014', 'Carla');
      const board = await apiCreateBoard(request, alice, { title: `E2E Rôles ${suffix()}` });
      await apiAddExistingMember(request, alice, board.id, carla);

      await openMembers(page, alice, board.id);
      // Propriétaire : libellé, ni sélecteur de rôle, ni retrait, ni « Quitter le tableau ».
      const ownerRow = personRow(page, alice.email);
      await expect(ownerRow).toContainText('Propriétaire');
      await expect(ownerRow.getByRole('combobox')).toHaveCount(0);
      await expect(ownerRow.getByRole('button', { name: 'Retirer' })).toHaveCount(0);
      await expect(
        settingsDrawer(page).getByRole('button', { name: 'Quitter le tableau' }),
      ).toHaveCount(0);

      const patched = waitApi(page, 'PATCH', `/api/boards/${board.id}/members/${carla.user.id}`);
      await personRow(page, carla.email).getByRole('combobox').selectOption('admin');
      const response = await patched;
      expect(response.status()).toBe(200);
      expectT1Upstream(response);
      expect(await response.json()).toMatchObject({ id: carla.user.id, role: 'admin' });

      await page.reload();
      await page.getByRole('button', { name: 'Partager' }).click();
      await expect(personRow(page, carla.email).getByRole('combobox')).toHaveValue('admin');
      const carlaView = (await apiBoards(request, carla)).find((b) => b.id === board.id);
      expect(carlaView?.role).toBe('admin');

      // Le propriétaire ne peut pas être modifié, même par l'API.
      const refused = await request.patch(`/api/boards/${board.id}/members/${alice.user.id}`, {
        data: { role: 'member' },
        headers: { Authorization: `Bearer ${carla.token}` },
      });
      expect(refused.status()).toBe(403);
      expect(await refused.json()).toEqual({
        error: 'Le rôle du propriétaire ne peut pas être modifié.',
      });
    },
  );

  test(
    'SC-BOARD-010 — simple membre : actions de gestion masquées, « Quitter le tableau » proposé',
    { tag: '@board' },
    async ({ page, request }) => {
      const alice = await createBoardAccount(request, 'SC-BOARD-010', 'Alice');
      const bob = await createBoardAccount(request, 'SC-BOARD-010', 'Bob');
      const carla = await createBoardAccount(request, 'SC-BOARD-010', 'Carla');
      const board = await apiCreateBoard(request, alice, { title: `E2E Membre ${suffix()}` });
      await apiAddExistingMember(request, alice, board.id, bob);
      await apiAddExistingMember(request, alice, board.id, carla);
      const invited = boardEmail('SC-BOARD-010-invite');
      expect((await apiAddMember(request, alice, board.id, invited)).status()).toBe(202);

      await openMembers(page, carla, board.id);
      await expect(personRow(page, bob.email)).toBeVisible();
      await expect(personRow(page, invited)).toBeVisible();
      await expect(
        settingsDrawer(page).getByRole('heading', { name: 'Inviter des collaborateurs' }),
      ).toHaveCount(0);
      await expect(inviteInput(page)).toHaveCount(0);
      await expect(settingsDrawer(page).getByRole('combobox')).toHaveCount(0);
      await expect(settingsDrawer(page).getByRole('button', { name: 'Retirer' })).toHaveCount(0);
      await expect(
        settingsDrawer(page).getByRole('button', { name: "Annuler l'invitation" }),
      ).toHaveCount(0);
      await expect(personRow(page, alice.email)).toContainText('Propriétaire');
      await expect(personRow(page, bob.email)).toContainText('Membre');
      await expect(
        settingsDrawer(page).getByRole('button', { name: 'Quitter le tableau' }),
      ).toBeVisible();

      // L'API refuse aussi (403, message inchangé).
      const refused = await apiAddMember(request, carla, board.id, boardEmail('SC-BOARD-010-x'));
      expect(refused.status()).toBe(403);
      expectT1Upstream(refused);
      expect(await refused.json()).toEqual({
        error: 'Action réservée aux administrateurs du tableau.',
      });
    },
  );

  test(
    'SC-BOARD-015 / SC-BOARD-033 — retrait d’un membre : confirmation, member_removed, le membre retiré reçoit « Tableau introuvable. »',
    { tag: '@board' },
    async ({ browser, page, request }) => {
      const alice = await createBoardAccount(request, 'SC-BOARD-015', 'Alice');
      const bob = await createBoardAccount(request, 'SC-BOARD-015', 'Bob');
      const board = await apiCreateBoard(request, alice, { title: `E2E Retrait ${suffix()}` });
      await apiAddExistingMember(request, alice, board.id, bob);
      await waitNotifications(request, bob, board.id, 1);

      await openMembers(page, alice, board.id);
      await personRow(page, bob.email).getByRole('button', { name: 'Retirer' }).click();
      const dialog = confirmDialog(page, `Retirer ${bob.name} ?`);
      await expect(dialog).toBeVisible();
      const removed = waitApi(page, 'DELETE', `/api/boards/${board.id}/members/${bob.user.id}`);
      await dialog.getByRole('button', { exact: true, name: 'Retirer' }).click();
      const response = await removed;

      expect(response.status()).toBe(204);
      expectT1Upstream(response);
      await expectToast(page, `${bob.name} a été retiré`);
      await expect(personRow(page, bob.email)).toHaveCount(0);
      expect((await apiBoards(request, bob)).map((b) => b.id)).not.toContain(board.id);
      // member_removed : sans lien vers le tableau (boardId nul, FA13).
      const removedMessage = `${alice.name} vous a retiré du tableau « ${board.title} »`;
      await expect
        .poll(
          async () =>
            (await apiAllNotifications(request, bob))
              .filter((n) => n.type === 'member_removed')
              .map((n) => [n.message, n.boardId]),
          { timeout: 30_000 },
        )
        .toEqual([[removedMessage, null]]);

      // Bob ouvre encore le tableau : réponse du non-membre (§5.3), toast puis accueil.
      const bobContext = await browser.newContext();
      const bobPage = await bobContext.newPage();
      await storeSession(bobPage, bob);
      const shell = waitApi(bobPage, 'GET', `/api/boards/${board.id}`);
      await bobPage.goto(`/boards/${board.id}`);
      const notFound = await shell;
      expect(notFound.status()).toBe(404);
      expectT1Upstream(notFound);
      expect(await notFound.json()).toEqual({ error: 'Tableau introuvable.' });
      await expectToast(bobPage, 'Tableau introuvable.');
      await expect(bobPage).toHaveURL(/\/boards$/);
      await bobContext.close();
    },
  );

  test(
    'SC-BOARD-016 — quitter un tableau : confirmation, retour à l’accueil sans le tableau, aucune notification',
    { tag: '@board' },
    async ({ page, request }) => {
      const alice = await createBoardAccount(request, 'SC-BOARD-016', 'Alice');
      const carla = await createBoardAccount(request, 'SC-BOARD-016', 'Carla');
      const board = await apiCreateBoard(request, alice, { title: `E2E Départ ${suffix()}` });
      await apiAddExistingMember(request, alice, board.id, carla);

      await openMembers(page, carla, board.id);
      await settingsDrawer(page).getByRole('button', { name: 'Quitter le tableau' }).click();
      const dialog = confirmDialog(page, 'Quitter ce tableau ?');
      await expect(dialog).toBeVisible();
      const left = waitApi(page, 'DELETE', `/api/boards/${board.id}/members/${carla.user.id}`);
      await dialog.getByRole('button', { exact: true, name: 'Quitter' }).click();
      const response = await left;

      expect(response.status()).toBe(204);
      expectT1Upstream(response);
      await expectToast(page, 'Vous avez quitté le tableau');
      await expect(page).toHaveURL(/\/boards$/);
      await expect(page.getByRole('heading', { name: 'Tous les tableaux' })).toBeVisible();
      await expect(boardTile(page, board.title)).toHaveCount(0);
      const shell = (await (await apiBoard(request, alice, board.id)).json()) as BoardShell;
      expect(shell.members.map((m) => m.id)).toEqual([alice.user.id]);
      // Aucune notification de départ (l'arrivée de Carla a notifié Carla seulement).
      expect(await apiNotifications(request, alice, board.id)).toEqual([]);

      // Le propriétaire ne peut pas partir.
      const refused = await request.delete(`/api/boards/${board.id}/members/${alice.user.id}`, {
        headers: { Authorization: `Bearer ${alice.token}` },
      });
      expect(refused.status()).toBe(403);
      expect(await refused.json()).toEqual({
        error: 'Le propriétaire ne peut pas quitter son tableau.',
      });
    },
  );
});
