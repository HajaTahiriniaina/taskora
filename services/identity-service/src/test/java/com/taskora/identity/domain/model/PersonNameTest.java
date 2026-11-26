package com.taskora.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskora.identity.domain.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class PersonNameTest {

  @Test
  @DisplayName("SC-IDENT-001 — le nom est trimé")
  void trims() {
    assertThat(PersonName.of("  Camille Martin ").value()).isEqualTo("Camille Martin");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   ", "\t\n\r\0\u000B"})
  @DisplayName("SC-IDENT-003 — nom absent, vide ou composé d'espaces → « Le champ \"name\" est obligatoire. »")
  void required(String raw) {
    assertThatThrownBy(() -> PersonName.of(raw))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Le champ \"name\" est obligatoire.");
  }

  @Test
  @DisplayName("SC-IDENT-004/015 — 150 caractères → tronqué à 120 points de code")
  void truncatesTo120() {
    assertThat(PersonName.of("n".repeat(150)).value()).hasSize(120);
  }

  @Test
  @DisplayName("SC-IDENT-004/015 — un emoji compte pour 1 point de code dans la troncature")
  void emojiCountsAsOne() {
    String name = "😀".repeat(150);
    String v = PersonName.of(name).value();
    assertThat(v.codePointCount(0, v.length())).isEqualTo(120);
    assertThat(v).isEqualTo("😀".repeat(120));
  }

  @Test
  @DisplayName("SC-IDENT-004 — trim AVANT troncature")
  void trimBeforeTruncate() {
    assertThat(PersonName.of("   " + "x".repeat(120) + "y").value()).isEqualTo("x".repeat(120));
  }
}
