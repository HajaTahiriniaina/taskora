package com.taskora.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskora.identity.domain.exception.ValidationException;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AvatarColorTest {

  @Test
  @DisplayName("SC-IDENT-001 — la palette est celle de Symfony, dans l'ordre")
  void palette() {
    assertThat(AvatarColor.PALETTE)
        .containsExactly(
            "#6366f1", "#ec4899", "#14b8a6", "#f59e0b", "#8b5cf6", "#ef4444", "#0ea5e9", "#22c55e");
  }

  @Test
  @DisplayName("SC-IDENT-001 — tirage aléatoire dans la palette, déterministe à graine égale")
  void randomFromPalette() {
    Set<String> seen = new HashSet<>();
    SplittableRandom r1 = new SplittableRandom(42);
    SplittableRandom r2 = new SplittableRandom(42);
    for (int i = 0; i < 200; i++) {
      String a = AvatarColor.random(r1).value();
      assertThat(AvatarColor.PALETTE).contains(a);
      assertThat(AvatarColor.random(r2).value()).isEqualTo(a);
      seen.add(a);
    }
    assertThat(seen).as("toutes les couleurs sont tirables").hasSize(8);
  }

  @ParameterizedTest
  @ValueSource(strings = {"#0EA5E9", "#0ea5e9", "#000000", "#FFFFFF", "#aBcDeF"})
  @DisplayName("SC-IDENT-016 — couleur #RRGGBB conservée telle quelle (casse comprise)")
  void validKeptAsIs(String raw) {
    assertThat(AvatarColor.parse(raw).value()).isEqualTo(raw);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "red",
        "#12345",
        "#1234567",
        "",
        "#0EA5E9\n",
        " #0EA5E9",
        "#0EA5E9 ",
        "#GGGGGG",
        "0EA5E9",
        "##0EA5E",
        "#0EA5E9\r"
      })
  @DisplayName(
      "SC-IDENT-031 E18 — couleur invalide (dont \\n final) → « Couleur d'avatar invalide. »")
  void invalidRejected(String raw) {
    assertThatThrownBy(() -> AvatarColor.parse(raw))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Couleur d'avatar invalide.");
  }
}
