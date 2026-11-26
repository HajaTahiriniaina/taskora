package com.taskora.identity.adapter.out.security;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.security.crypto.bcrypt.BCrypt;

/**
 * Moteur bcrypt Java pur (Spring Security {@link BCrypt}) : réservé au développement hors Linux
 * ({@code identity.password.bcrypt-engine=java}), environ 35 % plus lent que PHP (B1).
 */
final class JavaBcryptEngine implements BcryptEngine {

  @Override
  public @Nullable String crypt(byte[] input, String setting) {
    try {
      return BCrypt.hashpw(input, setting);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
