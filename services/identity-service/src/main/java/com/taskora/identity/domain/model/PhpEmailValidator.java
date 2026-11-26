package com.taskora.identity.domain.model;

/** Équivalent de filter_var($e, FILTER_VALIDATE_EMAIL) sans drapeau. */
public final class PhpEmailValidator {
  private PhpEmailValidator() {}

  public static boolean isValid(String email) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
