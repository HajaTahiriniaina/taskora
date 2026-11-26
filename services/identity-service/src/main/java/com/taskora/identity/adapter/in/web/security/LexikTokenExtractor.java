package com.taskora.identity.adapter.in.web.security;

import jakarta.servlet.http.HttpServletRequest;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.http.HttpHeaders;

/**
 * AuthorizationHeaderTokenExtractor de Lexik : l'en-tête doit se découper en exactement deux
 * morceaux sur l'espace, le premier valant « Bearer » (casse ignorée). Sinon : aucun jeton.
 */
public final class LexikTokenExtractor {
  private static final String PREFIX = "Bearer";

  /** Le jeton, ou null s'il est absent (→ « JWT Token not found »). */
  public @Nullable String extract(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || header.isEmpty()) {
      return null;
    }
    String[] parts = header.split(" ", -1);
    if (parts.length != 2 || !PREFIX.equalsIgnoreCase(parts[0]) || parts[1].isEmpty()) {
      return null;
    }
    return parts[1];
  }
}
