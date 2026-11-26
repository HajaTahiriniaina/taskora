package com.taskora.identity.adapter.out.security;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Moteur bcrypt natif (B1) : {@code crypt_rn} de libxcrypt ({@code libcrypt.so.1}) appelé par l'API
 * FFM, même algorithme Openwall en C que PHP. Squelette : implémentation par BackCodeur (ordre de
 * travail {@code phase-1-identity-perf-b1-workorder.md} §5.2).
 */
final class LibxcryptBcryptEngine implements BcryptEngine {
  static final String LIBRARY = "libcrypt.so.1";

  /** Résout {@code crypt_rn} dans {@value #LIBRARY} ; échec explicite si introuvable. */
  LibxcryptBcryptEngine() {
    this(LIBRARY);
  }

  /**
   * Résout {@code crypt_rn} dans la bibliothèque nommée.
   *
   * @throws IllegalStateException si la bibliothèque ou le symbole est introuvable
   */
  LibxcryptBcryptEngine(String library) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  @Override
  public @Nullable String crypt(byte[] input, String setting) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
