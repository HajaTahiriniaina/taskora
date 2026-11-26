package com.taskora.identity.adapter.in.web.security;

import com.taskora.identity.adapter.in.web.ErrorMessages;
import com.taskora.identity.application.port.in.AuthenticatedUser;
import com.taskora.identity.application.port.in.ResolveAuthenticatedUserUseCase;
import com.taskora.identity.domain.exception.TokenRejectedException;
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
 * Filtre JWT compatible Lexik (A1) : extraction « Bearer », validation RS256 et précédence des
 * messages du §8.1, puis rechargement du compte par username. Remplace le
 * BearerTokenAuthenticationFilter de Spring (extraction, tolérance et messages différents).
 */
public final class LexikCompatibleJwtFilter extends OncePerRequestFilter {
  private static final String PUBLIC_PREFIX = "/api/auth/";

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

  /** /api/auth/* est public : un jeton, même invalide, n'y est pas examiné (pare-feu login). */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return request.getRequestURI().startsWith(request.getContextPath() + PUBLIC_PREFIX);
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
      entryPoint.write(response, message(e));
      return;
    }
    List<SimpleGrantedAuthority> authorities =
        user.roles().stream().map(SimpleGrantedAuthority::new).toList();
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(user, null, authorities));
    SecurityContextHolder.setContext(context);
    chain.doFilter(request, response);
  }

  private static String message(TokenRejectedException e) {
    return switch (e.reason()) {
      case EXPIRED -> ErrorMessages.JWT_EXPIRED;
      case MISSING_USERNAME -> ErrorMessages.JWT_MISSING_USERNAME;
      case ACCOUNT_NOT_FOUND -> ErrorMessages.INVALID_CREDENTIALS;
      case INVALID -> ErrorMessages.JWT_INVALID;
    };
  }
}
