package com.taskora.board.application.port.out;

/** Vérification d'un JWT RS256 par la clé publique seule (B-ADR-2), sans tolérance d'horloge. */
public interface TokenVerifierPort {

  /**
   * Vérifie algorithme ({@code RS256} seul, avant toute cryptographie), signature, {@code exp}
   * (obligatoire, prioritaire) et {@code iat} (non futur).
   *
   * @param token jeton compact
   * @return revendications utiles
   * @throws com.taskora.board.domain.exception.TokenRejectedException jeton invalide ou expiré
   */
  VerifiedToken verify(String token);
}
