package com.taskora.identity.application.port.in;

/** Reprise des comptes Symfony (A14). */
public interface ImportLegacyUsersUseCase {
  /**
   * Copie taskora.user dans identity de façon idempotente, dans une transaction (A14, A18).
   *
   * @return rapport chiffré, sans donnée personnelle
   * @throws IllegalStateException si un contrôle préalable échoue (doublon normalisé, e-mail sous
   *     un autre id), avant toute écriture
   */
  ImportReport importAll();

  /**
   * Compare source et cible (nombres, empreintes CRC32, préfixes de hash, ids, auto-incrément).
   *
   * @return rapport cohérent ou liste des contrôles en échec
   */
  VerificationReport verify();
}
