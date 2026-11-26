package com.taskora.identity.domain.exception;

/** Erreur de validation : le message est le message exact renvoyé au client (400). */
public class ValidationException extends RuntimeException {
  public ValidationException(String message) {
    super(message);
  }
}
