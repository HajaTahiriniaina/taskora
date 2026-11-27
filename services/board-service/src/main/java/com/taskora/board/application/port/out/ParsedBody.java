package com.taskora.board.application.port.out;

import com.taskora.board.domain.exception.DomainMessages;
import com.taskora.board.domain.exception.ValidationException;
import java.util.Map;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Objet JSON analysé ; une clé absente diffère d'une clé à {@code null}.
 *
 * @param fields champs de premier niveau (chaîne, nombre, booléen, liste, table, ou {@code null})
 */
public record ParsedBody(Map<String, @Nullable Object> fields) {

  /**
   * Présence d'une clé.
   *
   * @param name nom
   * @return vrai si la clé est présente (même à {@code null})
   */
  public boolean has(String name) {
    return fields.containsKey(name);
  }

  /**
   * Champ texte (E22) : absent → vide ; présent et non chaîne → 400.
   *
   * @param name nom
   * @return valeur, ou vide si absent
   * @throws com.taskora.board.domain.exception.ValidationException {@code Le champ "<nom>" doit
   *     être une chaîne de caractères.}
   */
  public Optional<String> string(String name) {
    if (!has(name)) {
      return Optional.empty();
    }
    if (raw(name) instanceof String s) {
      return Optional.of(s);
    }
    throw new ValidationException(DomainMessages.notString(name));
  }

  /**
   * Champ booléen (E23) : absent → vide ; présent et non booléen ({@code null} compris) → 400.
   *
   * @param name nom
   * @return valeur, ou vide si absent
   * @throws com.taskora.board.domain.exception.ValidationException {@code Le champ "<nom>" doit
   *     être un booléen.}
   */
  public Optional<Boolean> bool(String name) {
    if (!has(name)) {
      return Optional.empty();
    }
    if (raw(name) instanceof Boolean b) {
      return Optional.of(b);
    }
    throw new ValidationException(DomainMessages.notBoolean(name));
  }

  /**
   * Valeur brute d'un champ.
   *
   * @param name nom
   * @return valeur, ou {@code null}
   */
  public @Nullable Object raw(String name) {
    return fields.get(name);
  }
}
