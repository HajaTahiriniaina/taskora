package com.taskora.board.application.port.out;

/**
 * Analyse du corps, appelée par le cas d'utilisation APRÈS la politique d'accès (B-ADR-3 point 3,
 * U13).
 */
public interface RequestBodyParser {

  /**
   * Corps vide → objet vide ; non JSON ou JSON non objet ({@code []}, {@code "x"}, {@code 42},
   * {@code true}, {@code null}) → 400.
   *
   * @param raw octets reçus
   * @return objet analysé
   * @throws com.taskora.board.domain.exception.ValidationException {@code JSON invalide.}
   */
  ParsedBody parse(byte[] raw);
}
