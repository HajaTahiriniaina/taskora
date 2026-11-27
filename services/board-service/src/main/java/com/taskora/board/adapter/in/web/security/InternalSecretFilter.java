package com.taskora.board.adapter.in.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code /internal/**} (B-ADR-5 points 5 et 6, U12) : seul le secret de l'appelant identity ({@code
 * INTERNAL_SECRET_IDENTITY_TO_BOARD}) est accepté, comparé en temps constant ; tout autre secret,
 * son absence ou un JWT → 401 {@code Accès interne refusé.} avant toute lecture du corps.
 */
public final class InternalSecretFilter extends OncePerRequestFilter {
  static final String HEADER = "X-Internal-Secret";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final byte[] expected;
  private final String deniedMessage;

  /**
   * Crée le filtre.
   *
   * @param identityToBoardSecret secret attendu (non vide, validé au démarrage)
   * @param deniedMessage corps du 401
   */
  public InternalSecretFilter(String identityToBoardSecret, String deniedMessage) {
    this.expected = identityToBoardSecret.getBytes(StandardCharsets.UTF_8);
    this.deniedMessage = deniedMessage;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(request.getContextPath() + "/internal/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String given = request.getHeader(HEADER);
    boolean accepted =
        given != null
            && expected.length > 0
            && MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8));
    if (!accepted) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response
          .getOutputStream()
          .write(JSON.writeValueAsBytes(java.util.Map.of("error", deniedMessage)));
      return;
    }
    chain.doFilter(request, response);
  }
}
