package com.taskora.identity.domain.exception;

/** Raison du refus d'un jeton (précédence du §8.1). */
public enum TokenRejection {
  INVALID,
  EXPIRED,
  MISSING_USERNAME,
  ACCOUNT_NOT_FOUND
}
