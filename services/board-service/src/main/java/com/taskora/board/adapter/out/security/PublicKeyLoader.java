package com.taskora.board.adapter.out.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Chargement de {@code public.pem} seul (B-ADR-2 point 1) ; ni clé privée ni passphrase. Échec → le
 * service ne démarre pas (message sans contenu de clé).
 */
public final class PublicKeyLoader {
  private static final String BEGIN = "-----BEGIN PUBLIC KEY-----";
  private static final String END = "-----END PUBLIC KEY-----";

  private PublicKeyLoader() {}

  /**
   * Lit une clé publique RSA PEM ({@code BEGIN PUBLIC KEY}).
   *
   * @param path chemin ({@code JWT_PUBLIC_KEY_PATH})
   * @return clé publique
   */
  public static RSAPublicKey load(Path path) {
    try {
      String pem = Files.readString(path, StandardCharsets.US_ASCII);
      int begin = pem.indexOf(BEGIN);
      int end = pem.indexOf(END);
      if (begin < 0 || end < begin) {
        throw new IllegalStateException(
            "JWT_PUBLIC_KEY_PATH : fichier sans clé publique PEM (BEGIN PUBLIC KEY)");
      }
      byte[] der =
          Base64.getMimeDecoder().decode(pem.substring(begin + BEGIN.length(), end).strip());
      return (RSAPublicKey)
          KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    } catch (IOException e) {
      throw new IllegalStateException("JWT_PUBLIC_KEY_PATH : clé publique illisible", e);
    } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
      throw new IllegalStateException("JWT_PUBLIC_KEY_PATH : clé publique RSA invalide", e);
    }
  }
}
