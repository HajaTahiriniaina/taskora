package com.taskora.identity.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskora.identity.domain.exception.ValidationException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PhpJsonBodyTest {

  private static PhpJsonBody parse(String s) {
    return PhpJsonBody.parse(s.getBytes(StandardCharsets.UTF_8));
  }

  private static String typeError(String key) {
    return "Le champ \"" + key + "\" doit être une chaîne de caractères.";
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"[]", "[1,2]", "\"texte\"", "42", "true", "null", "{", "texte", "{\"a\":}"})
  @DisplayName("SC-IDENT-029 E16 — JSON non objet ou invalide → « JSON invalide. »")
  void nonObjectRejected(String body) {
    assertThatThrownBy(() -> parse(body))
        .isInstanceOf(ValidationException.class)
        .hasMessage("JSON invalide.");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\n\t "})
  @DisplayName(
      "SC-IDENT-033 E20 — corps vide ou d'espaces → « JSON invalide. » (plus d'équivalence avec {})")
  void emptyRejected(String body) {
    assertThatThrownBy(() -> parse(body))
        .isInstanceOf(ValidationException.class)
        .hasMessage("JSON invalide.");
  }

  @Test
  @DisplayName("SC-IDENT-029 — {} accepté")
  void emptyObject() {
    PhpJsonBody b = parse("{}");
    assertThat(b.has("name")).isFalse();
    assertThat(b.requiredString("name")).isNull();
    assertThat(b.optionalString("name")).isNull();
  }

  @Test
  @DisplayName("SC-IDENT-030 — has / isSetLikePhp : null présent mais pas « set »")
  void hasAndIsSet() {
    PhpJsonBody b = parse("{\"a\":null,\"b\":\"\",\"c\":0}");
    assertThat(b.has("a")).isTrue();
    assertThat(b.isSetLikePhp("a")).isFalse();
    assertThat(b.isSetLikePhp("b")).isTrue();
    assertThat(b.isSetLikePhp("c")).isTrue();
    assertThat(b.has("d")).isFalse();
    assertThat(b.isSetLikePhp("d")).isFalse();
  }

  @Test
  @DisplayName(
      "SC-IDENT-030 — chaînes lues telles quelles (aucun trim à ce niveau, Unicode conservé)")
  void stringsAsIs() {
    PhpJsonBody b = parse("{\"name\":\"  Camille 😀 \",\"e\":\"\"}");
    assertThat(b.requiredString("name")).isEqualTo("  Camille 😀 ");
    assertThat(b.optionalString("name")).isEqualTo("  Camille 😀 ");
    assertThat(b.requiredString("e")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"123", "1.5", "true", "false", "null", "[]", "[\"a\"]", "{}", "{\"x\":\"y\"}"})
  @DisplayName(
      "SC-IDENT-030 E17 — requiredString : non-chaîne (null compris) → message exact, aucune conversion PHP")
  void requiredStringRejectsNonStrings(String value) {
    PhpJsonBody b = parse("{\"name\":" + value + "}");
    assertThatThrownBy(() -> b.requiredString("name"))
        .isInstanceOf(ValidationException.class)
        .hasMessage(typeError("name"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"123", "1.5", "true", "false", "[]", "[\"a\"]", "{}"})
  @DisplayName("SC-IDENT-030 E17 — optionalString : non-chaîne → message exact")
  void optionalStringRejectsNonStrings(String value) {
    PhpJsonBody b = parse("{\"avatarColor\":" + value + "}");
    assertThatThrownBy(() -> b.optionalString("avatarColor"))
        .isInstanceOf(ValidationException.class)
        .hasMessage(typeError("avatarColor"));
  }

  @Test
  @DisplayName("SC-IDENT-030 E17 — optionalString : null vaut « absent »")
  void optionalNullIsAbsent() {
    assertThat(parse("{\"newPassword\":null}").optionalString("newPassword")).isNull();
  }

  @Test
  @DisplayName(
      "SC-IDENT-034 E21 — \"0\" est une chaîne comme les autres (pas de sémantique empty() de PHP)")
  void zeroIsAString() {
    assertThat(parse("{\"newPassword\":\"0\"}").optionalString("newPassword")).isEqualTo("0");
  }

  @Test
  @DisplayName("SC-IDENT-030 — clés inconnues ignorées quel que soit leur type")
  void unknownKeysIgnored() {
    PhpJsonBody b = parse("{\"email\":42,\"name\":\"Camille\"}");
    assertThat(b.optionalString("name")).isEqualTo("Camille");
  }

  @Test
  @DisplayName("SC-IDENT-030 — le message cite la clé demandée")
  void messageNamesKey() {
    PhpJsonBody b = parse("{\"currentPassword\":1,\"newPassword\":2}");
    assertThatThrownBy(() -> b.optionalString("newPassword")).hasMessage(typeError("newPassword"));
    assertThatThrownBy(() -> b.optionalString("currentPassword"))
        .hasMessage(typeError("currentPassword"));
  }
}
