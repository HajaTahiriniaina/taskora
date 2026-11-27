package com.taskora.board.application.service;

import com.taskora.board.application.port.in.InvariantReport;
import com.taskora.board.application.port.in.VerifyBoardInvariantsUseCase;

/** Commande {@code verify} (board-migration.md §8). Squelette. */
public class InvariantVerificationService implements VerifyBoardInvariantsUseCase {

  /** Crée le service (dépendances ajoutées par BackCodeur). */
  public InvariantVerificationService() {
    // squelette
  }

  /** {@inheritDoc} */
  @Override
  public InvariantReport measure() {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /** {@inheritDoc} */
  @Override
  public boolean regressed(InvariantReport baseline, InvariantReport current) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
