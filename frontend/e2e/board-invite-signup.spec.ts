import { expect, test } from '@playwright/test';

import { randomPassword, TestAccount } from './support/accounts';
import {
  apiAddMember,
  apiBoard,
  apiBoards,
  apiCreateBoard,
  apiTrashBoard,
  bearer,
  boardEmail,
  BoardShell,
  boardTile,
  createBoardAccount,
  expectT1Upstream,
  suffix,
  waitApi,
  waitNotifications,
} from './support/boards';
import { readStoredToken, readStoredUser } from './support/session';

// Inscription d'un invité (identity.md SC-IDENT-005, phase-3 SC-BOARD-030) : identity-service
// consomme les invitations par POST /internal/invitations/claim (INVITATION_CLAIM_URL : Symfony
// avant la bascule de T1, board-service après).

test(
  'SC-IDENT-005 / SC-BOARD-030 — inscription d’un invité : membre des tableaux (corbeille comprise), invitations supprimées, board_invite par tableau, tableau visible dès /boards',
  { tag: '@board' },
  async ({ page, request }) => {
    const alice = await createBoardAccount(request, 'SC-IDENT-005', 'Alice');
    const s = suffix();
    const sprint = await apiCreateBoard(request, alice, { title: `E2E Sprint ${s}` });
    const roadmap = await apiCreateBoard(request, alice, { title: `E2E Roadmap ${s}` });
    const email = boardEmail('SC-IDENT-005-camille');
    for (const board of [sprint, roadmap]) {
      const invited = await apiAddMember(request, alice, board.id, email);
      expect(invited.status(), `invitation sur ${board.title}`).toBe(202);
      expectT1Upstream(invited);
    }
    // Un tableau dans la corbeille reçoit quand même l'adhésion (comportement actuel).
    await apiTrashBoard(request, alice, roadmap.id);

    const name = `Camille E2E ${s}`;
    await page.goto('/register');
    await page.getByLabel('Nom complet').fill(name);
    await page.getByLabel('Adresse e-mail').fill(email);
    await page.getByLabel('Mot de passe', { exact: true }).fill(randomPassword());
    const registered = waitApi(page, 'POST', '/api/auth/register');
    const home = waitApi(page, 'GET', '/api/boards');
    await page.getByRole('button', { name: 'Créer mon compte' }).click();
    expect((await registered).status()).toBe(201);

    // Le tableau ouvert est visible dès l'arrivée sur /boards (consommation synchrone).
    await expect(page).toHaveURL(/\/boards$/);
    const homeResponse = await home;
    expectT1Upstream(homeResponse);
    const homeBoards = (await homeResponse.json()) as { id: number; role: string }[];
    expect(homeBoards.map((b) => [b.id, b.role])).toEqual([[sprint.id, 'member']]);
    await expect(boardTile(page, sprint.title)).toContainText('Membre');
    await expect(boardTile(page, roadmap.title)).toHaveCount(0);

    const user = await readStoredUser(page);
    const token = await readStoredToken(page);
    expect(user !== null && token !== null, 'session ouverte').toBe(true);
    const camille: TestAccount = {
      email,
      name,
      password: '',
      token: token ?? '',
      user: user ?? { avatarColor: '', email, id: 0, name },
    };

    // Invitations supprimées ; adhésion « member » sur les deux tableaux.
    const sprintShell = (await (await apiBoard(request, alice, sprint.id)).json()) as BoardShell;
    expect(sprintShell.invitations).toEqual([]);
    expect(sprintShell.members.map((m) => [m.id, m.role])).toEqual([
      [alice.user.id, 'owner'],
      [camille.user.id, 'member'],
    ]);
    const restored = await request.post(`/api/trash/boards/${roadmap.id}/restore`, {
      headers: bearer(alice),
    });
    expect(restored.status()).toBe(200);
    const roadmapShell = (await (await apiBoard(request, alice, roadmap.id)).json()) as BoardShell;
    expect(roadmapShell.invitations).toEqual([]);
    expect(roadmapShell.members.map((m) => [m.id, m.role])).toEqual([
      [alice.user.id, 'owner'],
      [camille.user.id, 'member'],
    ]);
    expect((await apiBoards(request, camille)).map((b) => b.id).sort()).toEqual(
      [sprint.id, roadmap.id].sort(),
    );

    // Une notification board_invite par tableau, au nom de l'auteur de l'invitation.
    for (const board of [sprint, roadmap]) {
      expect(await waitNotifications(request, camille, board.id, 1)).toEqual([
        {
          message: `${alice.name} vous a ajouté au tableau « ${board.title} »`,
          type: 'board_invite',
        },
      ]);
    }
  },
);
