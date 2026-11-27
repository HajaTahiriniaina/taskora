package com.taskora.board.adapter.in.web.security;

import com.taskora.board.application.port.in.AuthenticatedUser;
import com.taskora.board.application.port.in.ResolveAuthenticatedUserUseCase;
import com.taskora.board.domain.exception.TokenRejectedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filtre JWT compatible Lexik (repris d'identity, B-ADR-2) : extraction « Bearer », validation
 * RS256 avec la clé publique seule et précédence des messages de §8.1, puis résolution de
 * l'appelant ({@code uid} ou {@code username}) par une seule lecture de {@code taskora.user}.
 * Jamais appliqué à {@code /internal/**} (secret par appelant, jamais de JWT).
 */
public final class LexikCompatibleJwtFilter extends OncePerRequestFilter {
  private static final List<SimpleGrantedAuthority> AUTHORITIES =
      List.of(new SimpleGrantedAuthority("ROLE_USER"));

  private final ResolveAuthenticatedUserUseCase resolver;
  private final LexikTokenExtractor extractor;
  private final LexikAuthenticationEntryPoint entryPoint;

  public LexikCompatibleJwtFilter(
      ResolveAuthenticatedUserUseCase resolver,
      LexikTokenExtractor extractor,
      LexikAuthenticationEntryPoint entryPoint) {
    this.resolver = resolver;
    this.extractor = extractor;
    this.entryPoint = entryPoint;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String token = extractor.extract(request);
    if (token == null) {
      // Pas de jeton : la règle d'autorisation déclenche le point d'entrée (« JWT Token not found
      // »).
      chain.doFilter(request, response);
      return;
    }
    AuthenticatedUser user;
    try {
      user = resolver.resolve(token);
    } catch (TokenRejectedException e) {
      SecurityContextHolder.clearContext();
      entryPoint.write(response, e.rejection().message());
      return;
    }
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(user, null, AUTHORITIES));
    SecurityContextHolder.setContext(context);
    chain.doFilter(request, response);
  }
}
