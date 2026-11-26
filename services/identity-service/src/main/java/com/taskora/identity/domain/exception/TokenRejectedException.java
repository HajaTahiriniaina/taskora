package com.taskora.identity.domain.exception;

/** Jeton refusé, avec sa raison. */
public class TokenRejectedException extends RuntimeException {
  private final TokenRejection reason;

  /**
   * Crée l'exception.
   *
   * @param reason raison, qui détermine le message 401 de Lexik
   */
  public TokenRejectedException(TokenRejection reason) {
    super(reason.name());
    this.reason = reason;
  }

  /**
   * Raison du refus.
   *
   * @return raison
   */
  public TokenRejection reason() {
    return reason;
  }
}
