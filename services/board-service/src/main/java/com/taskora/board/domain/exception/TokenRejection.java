package com.taskora.board.domain.exception;

/** Motifs de refus d'un jeton, avec le message Lexik exact (corps {@code {"code":401,…}}). */
public enum TokenRejection {
  /** En-tête absent ou mal formé. */
  NOT_FOUND("JWT Token not found"),
  /** Signature, algorithme, format, {@code iat} futur, revendications incohérentes. */
  INVALID("Invalid JWT Token"),
  /** Jeton expiré (prioritaire sur une signature fausse). */
  EXPIRED("Expired JWT Token"),
  /** Ni {@code uid} ni {@code username}. */
  NO_USERNAME("Unable to find key \"username\" in the token payload."),
  /** Compte introuvable dans {@code taskora.user}. */
  UNKNOWN_ACCOUNT("Invalid credentials.");

  private final String message;

  TokenRejection(String message) {
    this.message = message;
  }

  /**
   * Message Lexik exact.
   *
   * @return message du corps 401
   */
  public String message() {
    return message;
  }
}
