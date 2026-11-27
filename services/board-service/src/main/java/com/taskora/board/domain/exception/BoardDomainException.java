package com.taskora.board.domain.exception;

import java.io.Serial;

/** Exception métier de board-service : son message est le texte exact du contrat. */
public abstract class BoardDomainException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  /**
   * Crée l'exception.
   *
   * @param message texte exact du contrat
   */
  protected BoardDomainException(String message) {
    super(message);
  }
}
