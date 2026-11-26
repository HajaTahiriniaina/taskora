package com.taskora.identity.domain.exception;

/** Erreur de validation : le message est le message exact renvoyé au client (400). */
public class ValidationException extends RuntimeException {
  /**
   * Crée l'exception.
   *
   * @param message message exact renvoyé au client, sans donnée saisie
   */
  public ValidationException(String message) {
    super(message);
  }
}
