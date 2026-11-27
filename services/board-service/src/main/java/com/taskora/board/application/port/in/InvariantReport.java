package com.taskora.board.application.port.in;

import java.util.Map;

/**
 * Rapport de {@code verify} : des nombres seulement, par compteur nommé {@code V1…V8} (par exemple
 * {@code V4.boards_without_single_owner}).
 *
 * @param counters compteurs par nom (préfixe {@code V1} à {@code V8})
 */
public record InvariantReport(Map<String, Long> counters) {}
