package com.taskora.identity.domain.model;

import com.taskora.identity.domain.exception.PasswordTooLongException;
import com.taskora.identity.domain.exception.ValidationException;
import java.nio.charset.StandardCharsets;

/** Mot de passe en clair, jamais affiché. */
public final class PlainPassword {
  public static final int MIN_LENGTH = 8;
  public static final int MAX_BYTES = 4096;
  static final String TOO_SHORT = "Le mot de passe doit contenir au moins 8 caractères.";

  private final String value;

  private PlainPassword(String value) {
    this.value = value;
  }

  public static PlainPassword of(String raw) {
    return new PlainPassword(raw == null ? "" : raw);
  }

  public String value() {
    return value;
  }

  public int codePointLength() {
    return PhpString.length(value);
  }

  public int utf8Length() {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  public boolean isEmpty() {
    return value.isEmpty();
  }

  /** Au moins 8 points de code, sinon ValidationException. */
  public void requireMinimumLength() {
    if (codePointLength() < MIN_LENGTH) {
      throw new ValidationException(TOO_SHORT);
    }
  }

  /** Au plus 4096 octets UTF-8, sinon PasswordTooLongException (E15). */
  public void requireMaximumLength() {
    if (utf8Length() > MAX_BYTES) {
      throw new PasswordTooLongException();
    }
  }

  @Override
  public String toString() {
    return "PlainPassword[***]";
  }
}
