package com.taskora.board.adapter.in.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code /internal/**} (B-ADR-5 points 5 et 6, U12, §12.4 B1) : appliqué à toute requête de la
 * chaîne interne (choisie sur le chemin routé, décodé et normalisé) ; seul le secret de l'appelant
 * identity ({@code INTERNAL_SECRET_IDENTITY_TO_BOARD}) est accepté, comparé en temps constant ;
 * tout autre secret, son absence ou un JWT → 401 {@code Accès interne refusé.} avant toute lecture
 * du corps.
 */
public final class InternalSecretFilter extends OncePerRequestFilter {
  static final String HEADER = "X-Internal-Secret";

  /**
   * Autorité posée sur une requête interne acceptée (exigée par la chaîne {@code /internal/**}).
   */
  public static final String ROLE = "INTERNAL_IDENTITY";

  private static final String CALLER = "identity";
  private static final List<SimpleGrantedAuthority> AUTHORITIES =
      List.of(new SimpleGrantedAuthority("ROLE_" + ROLE));
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
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String given = request.getHeader(HEADER);
    boolean accepted =
        given != null
            && expected.length > 0
            && MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8));
    if (!accepted) {
      SecurityContextHolder.clearContext();
      writeDenied(response);
      return;
    }
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new PreAuthenticatedAuthenticationToken(CALLER, null, AUTHORITIES));
    SecurityContextHolder.setContext(context);
    chain.doFilter(request, response);
  }

  /**
   * Écrit le 401 {@code {"error":"Accès interne refusé."}} (aussi utilisé comme point d'entrée et
   * refus d'accès de la chaîne {@code /internal/**}).
   */
  public void writeDenied(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getOutputStream().write(JSON.writeValueAsBytes(Map.of("error", deniedMessage)));
  }
}
