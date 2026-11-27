package com.taskora.board.domain.exception;

import java.io.Serial;

/** 400 avec le message exact du contrat. */
public class ValidationException extends BoardDomainException {
  @Serial private static final long serialVersionUID = 1L;

  /**
   * Crée l'exception.
   *
   * @param message texte exact du contrat
   */
  public ValidationException(String message) {
    super(message);
  }
}
