package com.taskora.identity.adapter.out.security;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Moteur bcrypt brut (B1) : calcule {@code crypt(input, setting)} au sens de crypt(3).
 *
 * <p>{@code input} est l'entrée effective de bcrypt (au plus 72 octets, sans NUL, déjà pré-hachée
 * par {@link SymfonyCompatiblePasswordHasher#bcryptInput(String)}) ; {@code setting} est un sel
 * ({@code $2y$13$} + 22 caractères) ou un hash complet. Retourne le hash de 60 caractères, ou
 * {@code null} si le calcul échoue (réglage invalide), jamais d'exception.
 */
interface BcryptEngine {

  /**
   * Calcule le hash bcrypt.
   *
   * @param input entrée effective (≤ 72 octets, sans NUL)
   * @param setting sel ou hash complet ($2y$, $2a$, $2b$)
   * @return le hash de 60 caractères, ou {@code null} en cas d'échec
   */
  @Nullable String crypt(byte[] input, String setting);
}
