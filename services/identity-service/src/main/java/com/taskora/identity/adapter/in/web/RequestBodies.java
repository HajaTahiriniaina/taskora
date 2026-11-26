package com.taskora.identity.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Lecture du corps brut, quel que soit le Content-Type (pas de liaison Jackson automatique), bornée
 * à 64 Kio (A18) : Content-Length au-delà → refus immédiat ; sinon lecture d'au plus MAX_BYTES + 1
 * octets, ce qui couvre aussi Transfer-Encoding: chunked, sans lire la suite.
 */
final class RequestBodies {
  static final int MAX_BYTES = 65_536;

  private RequestBodies() {}

  static byte[] read(HttpServletRequest request) {
    if (request.getContentLengthLong() > MAX_BYTES) {
      throw new RequestBodyTooLargeException();
    }
    byte[] body;
    try {
      body = request.getInputStream().readNBytes(MAX_BYTES + 1);
    } catch (IOException e) {
      throw new UncheckedIOException("Lecture du corps de la requête impossible", e);
    }
    if (body.length > MAX_BYTES) {
      throw new RequestBodyTooLargeException();
    }
    return body;
  }
}
