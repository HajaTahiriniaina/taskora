package com.taskora.board.domain.exception;

import java.io.Serial;

/** 409 avec le message exact du contrat (adhésion ou invitation existante). */
public class ConflictException extends BoardDomainException {
  @Serial private static final long serialVersionUID = 1L;

  /**
   * Crée l'exception.
   *
   * @param message texte exact du contrat
   */
  public ConflictException(String message) {
    super(message);
  }
}
