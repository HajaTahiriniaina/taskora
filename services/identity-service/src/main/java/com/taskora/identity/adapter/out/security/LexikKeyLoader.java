package com.taskora.identity.adapter.out.security;

import java.nio.file.Path;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/** Lecture de la paire de clés Lexik (PKCS#8 chiffré, A1). */
public class LexikKeyLoader {

  public LexikKeyLoader(Path privateKey, Path publicKey, char[] passphrase) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  public RSAPrivateKey privateKey() {
    throw new UnsupportedOperationException("BackCodeur");
  }

  public RSAPublicKey publicKey() {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
