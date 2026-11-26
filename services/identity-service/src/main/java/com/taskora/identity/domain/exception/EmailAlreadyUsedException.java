package com.taskora.identity.domain.exception;

/** Exception de domaine (voir docs/architecture/backend-hexagonal.md A6). */
public class EmailAlreadyUsedException extends RuntimeException {
  /** Crée l'exception (409), sans l'e-mail, qui ne doit pas être journalisé. */
  public EmailAlreadyUsedException() {
    super();
  }
}
