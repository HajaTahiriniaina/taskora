package com.taskora.identity.domain.exception;

/** Exception de domaine (voir docs/architecture/backend-hexagonal.md A6). */
public class InvalidCredentialsException extends RuntimeException {
  public InvalidCredentialsException() {
    super();
  }
}
