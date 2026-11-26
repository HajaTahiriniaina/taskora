package com.taskora.identity.domain.exception;

/** Jeton refusé, avec sa raison. */
public class TokenRejectedException extends RuntimeException {
  private final TokenRejection reason;

  public TokenRejectedException(TokenRejection reason) {
    super(reason.name());
    this.reason = reason;
  }

  public TokenRejection reason() {
    return reason;
  }
}
