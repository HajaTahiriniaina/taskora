package com.taskora.board.application.port.out;

import java.util.Map;

/** Requêtes de {@code verify} (formes SQL de board-facts.sql), lecture seule. */
public interface InvariantsQueryPort {

  /**
   * Compteurs d'écarts V1 à V8.
   *
   * @return compteurs par nom
   */
  Map<String, Long> countViolations();
}
