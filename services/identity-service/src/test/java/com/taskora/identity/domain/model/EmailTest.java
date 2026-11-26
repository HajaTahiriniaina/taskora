package com.taskora.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskora.identity.domain.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class EmailTest {

  @Test
  @DisplayName("SC-IDENT-001 — l'e-mail d'inscription est trimé et mis en minuscules")
  void normalizesRegistrationEmail() {
    assertThat(Email.fromRegistration(" Camille@Exemple.FR ").value())
        .isEqualTo("camille@exemple.fr");
  }

  @Test
  @DisplayName("SC-IDENT-001 — trim PHP : \\t \\n \\r \\0 \\x0B retirés, espace insécable conservé")
  void phpTrimOnly() {
    assertThat(Email.fromRegistration("\t\n\r\0\u000Bcamille@exemple.fr\u000B\0").value())
        .isEqualTo("camille@exemple.fr");
    assertThatThrownBy(() -> Email.fromRegistration(" camille@exemple.fr"))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Adresse e-mail invalide.");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   ", "\t\n"})
  @DisplayName(
      "SC-IDENT-003 — e-mail absent, vide ou blanc → « Le champ \"email\" est obligatoire. »")
  void requiredEmail(String raw) {
    assertThatThrownBy(() -> Email.fromRegistration(raw))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Le champ \"email\" est obligatoire.");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"pas-un-email", "camille@", "@exemple.fr", "a b@exemple.fr", "x@exemple..fr"})
  @DisplayName("SC-IDENT-003 — format refusé → « Adresse e-mail invalide. »")
  void invalidFormat(String raw) {
    assertThatThrownBy(() -> Email.fromRegistration(raw))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Adresse e-mail invalide.");
  }

  /**
   * Domaine fait de labels de 60 caractères au plus (FILTER_VALIDATE_EMAIL limite un label à 63).
   */
  private static String labels(char c, int length) {
    StringBuilder sb = new StringBuilder();
    while (sb.length() < length) {
      if (sb.length() > 0) {
        sb.append('.');
      }
      sb.append(String.valueOf(c).repeat(Math.min(60, length - sb.length())));
    }
    return sb.toString();
  }

  @Test
  @DisplayName("SC-IDENT-004 — 180 caractères exactement → conservé")
  void exactly180Kept() {
    String e = "a".repeat(40) + "@" + labels('d', 136) + ".fr";
    assertThat(e).hasSize(180);
    assertThat(Email.fromRegistration(e).value()).isEqualTo(e);
  }

  @Test
  @DisplayName(
      "SC-IDENT-004 — 182 caractères valides, tronqués à 180 AVANT validation → finit par « . » → invalide")
  void truncatedBeforeValidation() {
    String prefix =
        "a".repeat(40) + "@" + labels('d', 138) + "."; // 180 caractères terminés par un point
    assertThat(prefix).hasSize(180);
    assertThatThrownBy(() -> Email.fromRegistration(prefix + "fr"))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Adresse e-mail invalide.");
  }

  @Test
  @DisplayName("SC-IDENT-004 — troncature en points de code, puis minuscules")
  void truncatesThenLowercases() {
    String raw =
        "A".repeat(40) + "@" + labels('D', 137) + ".FRX"; // 182 → 180 : se termine par ".F"
    Email e = Email.fromRegistration(raw);
    assertThat(e.value()).hasSize(180).isEqualTo("a".repeat(40) + "@" + labels('d', 137) + ".f");
  }

  @Test
  @DisplayName("SC-IDENT-007 — un e-mail stocké est repris tel quel")
  void storedAsIs() {
    assertThat(Email.ofStored("camille@exemple.fr").value()).isEqualTo("camille@exemple.fr");
  }
}
