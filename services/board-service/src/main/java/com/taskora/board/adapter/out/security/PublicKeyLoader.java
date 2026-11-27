package com.taskora.board.adapter.out.security;

import java.nio.file.Path;
import java.security.interfaces.RSAPublicKey;

/**
 * Chargement de {@code public.pem} seul (B-ADR-2 point 1) ; ni clé privée ni passphrase. Échec → le
 * service ne démarre pas (message sans contenu de clé).
 */
public final class PublicKeyLoader {
  private PublicKeyLoader() {}

  /**
   * Lit une clé publique RSA PEM ({@code BEGIN PUBLIC KEY}).
   *
   * @param path chemin ({@code JWT_PUBLIC_KEY_PATH})
   * @return clé publique
   */
  public static RSAPublicKey load(Path path) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
