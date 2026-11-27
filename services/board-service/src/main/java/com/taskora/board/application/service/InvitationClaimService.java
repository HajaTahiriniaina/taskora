package com.taskora.board.application.service;

import com.taskora.board.application.port.in.ClaimInvitationsUseCase;
import com.taskora.board.application.port.in.ClaimResult;

/** Consommation des invitations (SC-BOARD-030). Squelette. */
public class InvitationClaimService implements ClaimInvitationsUseCase {

  /** Crée le service (dépendances ajoutées par BackCodeur). */
  public InvitationClaimService() {
    // squelette
  }

  /** {@inheritDoc} */
  @Override
  public ClaimResult claim(byte[] rawBody) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
