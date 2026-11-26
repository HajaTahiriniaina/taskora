package com.taskora.identity.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Lecture du corps brut, quel que soit le Content-Type (pas de liaison Jackson automatique). */
final class RequestBodies {
  private RequestBodies() {}

  static byte[] read(HttpServletRequest request) {
    try {
      return request.getInputStream().readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Lecture du corps de la requête impossible", e);
    }
  }
}
