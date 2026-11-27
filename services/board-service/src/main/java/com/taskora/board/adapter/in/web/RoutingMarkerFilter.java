package com.taskora.board.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Marqueur {@code X-Taskora-Backend: board} (SC-BOARD-022), activé seulement par {@code
 * BOARD_ROUTING_MARKER=on} : vérifie en recette l'amont réellement servi par nginx.
 */
public class RoutingMarkerFilter extends OncePerRequestFilter {
  static final String HEADER = "X-Taskora-Backend";

  private final boolean enabled;

  public RoutingMarkerFilter(boolean enabled) {
    this.enabled = enabled;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !enabled;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader(HEADER, "board");
    chain.doFilter(request, response);
  }
}
