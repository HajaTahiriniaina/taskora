package com.taskora.identity.domain.model;

/** Mot de passe en clair, jamais affiché. */
public final class PlainPassword {
  private PlainPassword() {}

  public static PlainPassword of(String raw) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  public String value() {
    throw new UnsupportedOperationException("BackCodeur");
  }

  public int codePointLength() {
    throw new UnsupportedOperationException("BackCodeur");
  }

  public int utf8Length() {
    throw new UnsupportedOperationException("BackCodeur");
  }

  public boolean isEmpty() {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /** Au moins 8 points de code, sinon ValidationException. */
  public void requireMinimumLength() {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /** Au plus 4096 octets UTF-8, sinon PasswordTooLongException (E15). */
  public void requireMaximumLength() {
    throw new UnsupportedOperationException("BackCodeur");
  }

  @Override
  public String toString() {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
