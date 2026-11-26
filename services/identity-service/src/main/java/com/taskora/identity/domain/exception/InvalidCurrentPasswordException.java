package com.taskora.identity.domain.exception;

/** Exception de domaine (voir docs/architecture/backend-hexagonal.md A6). */
public class InvalidCurrentPasswordException extends RuntimeException {
  /** Crée l'exception (400 « Mot de passe actuel incorrect. »). */
  public InvalidCurrentPasswordException() {
    super();
  }
}
