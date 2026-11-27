package com.taskora.board.domain.exception;

import java.io.Serial;

/** 403 avec le message exact du contrat (gestion, propriétaire). */
public class ForbiddenException extends BoardDomainException {
  @Serial private static final long serialVersionUID = 1L;

  /**
   * Crée l'exception.
   *
   * @param message texte exact du contrat
   */
  public ForbiddenException(String message) {
    super(message);
  }
}
