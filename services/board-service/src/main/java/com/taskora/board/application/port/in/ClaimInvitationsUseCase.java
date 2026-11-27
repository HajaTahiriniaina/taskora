package com.taskora.board.application.port.in;

/** Consommation des invitations d'un nouvel inscrit (SC-BOARD-030). */
public interface ClaimInvitationsUseCase {

  /**
   * Analyse le corps {@code InvitationClaimRequest}, puis transforme les invitations en adhésions
   * dans une transaction ; idempotent.
   *
   * @param rawBody corps brut
   * @return adhésions créées
   */
  ClaimResult claim(byte[] rawBody);
}
