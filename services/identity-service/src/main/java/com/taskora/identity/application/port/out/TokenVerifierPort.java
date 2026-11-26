package com.taskora.identity.application.port.out;

/** Vérification de JWT (lève TokenRejectedException). */
public interface TokenVerifierPort {
  /**
   * Valide un jeton selon la précédence de Lexik (§8.1), tolérance d'horloge nulle.
   *
   * @param token jeton compact
   * @return revendications d'un jeton valide
   * @throws com.taskora.identity.domain.exception.TokenRejectedException jeton refusé, avec sa
   *     raison
   */
  VerifiedToken verify(String token);
}
