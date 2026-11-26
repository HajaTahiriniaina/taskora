package com.taskora.identity.application.port.in;

/** Résolution d'un jeton en utilisateur authentifié (lève TokenRejectedException). */
public interface ResolveAuthenticatedUserUseCase {
  /**
   * Valide un jeton (précédence Lexik, §8.1) et recharge le compte par son e-mail.
   *
   * @param token jeton extrait de l'en-tête Authorization
   * @return utilisateur authentifié et rôles effectifs
   * @throws com.taskora.identity.domain.exception.TokenRejectedException jeton refusé ou compte
   *     disparu
   */
  AuthenticatedUser resolve(String token);
}
