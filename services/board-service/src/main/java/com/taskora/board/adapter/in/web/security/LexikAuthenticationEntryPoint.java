package com.taskora.board.adapter.in.web.security;

import com.taskora.board.domain.exception.TokenRejection;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

/** 401 au format Lexik : {"code":401,"message":…} et WWW-Authenticate: Bearer (A4). */
public final class LexikAuthenticationEntryPoint implements AuthenticationEntryPoint {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Aucun jeton dans la requête d'une route protégée. */
  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    write(response, TokenRejection.NOT_FOUND.message());
  }

  public void write(HttpServletResponse response, String message) throws IOException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", HttpServletResponse.SC_UNAUTHORIZED);
    body.put("message", message);
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getOutputStream().write(JSON.writeValueAsBytes(body));
  }
}
