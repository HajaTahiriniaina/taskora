package com.taskora.board.application.port.in;

/** Commande {@code verify} (board-migration.md §8), lecture seule. */
public interface VerifyBoardInvariantsUseCase {

  /**
   * Mesure les invariants V1 à V8.
   *
   * @return compteurs
   */
  InvariantReport measure();

  /**
   * Compare un état mesuré à l'état initial : un compteur en hausse est une régression.
   *
   * @param baseline état initial
   * @param current état mesuré
   * @return vrai si au moins un compteur a augmenté
   */
  boolean regressed(InvariantReport baseline, InvariantReport current);
}
