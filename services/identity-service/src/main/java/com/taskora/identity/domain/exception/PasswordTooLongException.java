package com.taskora.identity.domain.exception;

/** Exception de domaine (voir docs/architecture/backend-hexagonal.md A6). */
public class PasswordTooLongException extends RuntimeException {
  /** Crée l'exception (400, plus de 4096 octets UTF-8, E15). */
  public PasswordTooLongException() {
    super();
  }
}
