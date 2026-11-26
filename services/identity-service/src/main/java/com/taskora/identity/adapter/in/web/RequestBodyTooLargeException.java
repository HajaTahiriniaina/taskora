package com.taskora.identity.adapter.in.web;

import java.io.Serial;

/** Corps de requête au-delà de {@link RequestBodies#MAX_BYTES} octets → 413 (A18, SC-IDENT-035). */
public class RequestBodyTooLargeException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  /** Crée l'exception, sans message ni donnée de la requête. */
  public RequestBodyTooLargeException() {
    super(ErrorMessages.BODY_TOO_LARGE);
  }
}
