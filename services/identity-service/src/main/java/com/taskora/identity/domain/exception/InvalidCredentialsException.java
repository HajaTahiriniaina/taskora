package com.taskora.identity.domain.exception;

/** Exception de domaine (voir docs/architecture/backend-hexagonal.md A6). */
public class InvalidCredentialsException extends RuntimeException {
  /** Crée l'exception (401), identique pour un e-mail inconnu et un mot de passe faux. */
  public InvalidCredentialsException() {
    super();
  }
}
