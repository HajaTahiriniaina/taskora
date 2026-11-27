package com.taskora.board.domain.exception;

import java.io.Serial;

/** Jeton refusé (401 au format Lexik). */
public class TokenRejectedException extends BoardDomainException {
  @Serial private static final long serialVersionUID = 1L;

  private final TokenRejection rejection;

  /**
   * Crée l'exception.
   *
   * @param rejection motif
   */
  public TokenRejectedException(TokenRejection rejection) {
    super(rejection.message());
    this.rejection = rejection;
  }

  /**
   * Motif du refus.
   *
   * @return motif
   */
  public TokenRejection rejection() {
    return rejection;
  }
}
