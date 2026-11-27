package com.taskora.board.application.port.in;

/** Résolution de l'appelant à partir d'un jeton (B-ADR-2, SC-BOARD-020). */
public interface ResolveAuthenticatedUserUseCase {

  /**
   * Vérifie le jeton puis résout le compte : {@code uid} si présent, sinon {@code username}
   * (collation de {@code taskora.user.email}) ; {@code uid} et {@code username} incohérents → jeton
   * invalide.
   *
   * @param token jeton compact (sans le préfixe {@code Bearer})
   * @return appelant
   * @throws com.taskora.board.domain.exception.TokenRejectedException jeton refusé ou compte
   *     introuvable
   */
  AuthenticatedUser resolve(String token);
}
