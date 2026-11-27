package com.taskora.board.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskora.board.domain.exception.ValidationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SC-BOARD-009 à 013, 031 — e-mail d'ajout : trim PHP → troncature 180 → minuscules →
 * FILTER_VALIDATE_EMAIL. Le golden d'identity (sortie réelle de PHP) est réutilisé tel quel : même
 * fichier, pas de copie (../identity-service/src/test/resources/compat/).
 */
class InvitationEmailTest {
  static final Path GOLDEN =
      Path.of("../identity-service/src/test/resources/compat/php-filter-validate-email.json");

  static Stream<Arguments> golden() throws Exception {
    assertThat(Files.exists(GOLDEN)).as("golden d'identity introuvable : %s", GOLDEN).isTrue();
    JsonNode cases = JsonMapper.builder().build().readTree(Files.readString(GOLDEN));
    List<Arguments> out = new ArrayList<>();
    for (JsonNode c : cases) {
      String input = c.get("input").asString();
      // Seuls les cas que la normalisation (trim, 180, minuscules) laisse intacts hors casse.
      boolean untouched =
          !input.isEmpty()
              && input.codePointCount(0, input.length()) <= 180
              && input.equals(input.strip())
              && input.chars().noneMatch(ch -> ch == 0 || ch == 0x0B || ch > 0x7F);
      if (untouched) {
        out.add(Arguments.of(input, c.get("valid").asBoolean()));
      }
    }
    assertThat(out).as("cas du golden utilisables").hasSizeGreaterThan(50);
    return out.stream();
  }

  @ParameterizedTest(name = "[{index}] {0} → {1}")
  @MethodSource("golden")
  @DisplayName("SC-BOARD-011 — FILTER_VALIDATE_EMAIL identique à PHP (golden d'identity)")
  void phpGolden(String input, boolean valid) {
    if (valid) {
      assertThat(InvitationEmail.of(input).value())
          .isEqualTo(input.toLowerCase(java.util.Locale.ROOT));
    } else {
      assertThatThrownBy(() -> InvitationEmail.of(input))
          .isInstanceOf(ValidationException.class)
          .hasMessage("Adresse e-mail invalide.");
    }
  }

  @Test
  @DisplayName("SC-BOARD-009 — « BOB@exemple.fr » entouré d'espaces PHP → trim puis minuscules")
  void trimAndLower() {
    assertThat(InvitationEmail.of(" \t BOB@Exemple.FR \n\0").value()).isEqualTo("bob@exemple.fr");
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"", " ", "\t\n\r", "\u000B", "\0"})
  @DisplayName("SC-BOARD-011 — vide après trim → 400 Le champ \"email\" est obligatoire.")
  void required(String raw) {
    assertThatThrownBy(() -> InvitationEmail.of(raw))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Le champ \"email\" est obligatoire.");
  }

  @Test
  @DisplayName(
      "SC-BOARD-031 — 200 caractères : tronqué à 180 PUIS validé → 400 Adresse e-mail invalide.")
  void truncatedThenValidated() {
    // Valide entier, invalide une fois tronqué à 180 (finit par « . ») : vérifié en PHP 8.4.28.
    String email =
        "a".repeat(60)
            + "@"
            + "b".repeat(60)
            + "."
            + "c".repeat(57)
            + "."
            + "d".repeat(15)
            + ".test";
    assertThat(email).hasSize(200);
    assertThat(email.substring(0, 180)).endsWith(".");
    assertThatThrownBy(() -> InvitationEmail.of(email))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Adresse e-mail invalide.");
  }

  @Test
  @DisplayName(
      "SC-BOARD-031 — plus de 180 caractères, valide une fois tronqué → les 180 premiers caractères")
  void truncatedThenAccepted() {
    String email = "a".repeat(60) + "@" + "b".repeat(60) + "." + "c".repeat(60) + ".exemple.test";
    assertThat(email.length()).isGreaterThan(180);
    assertThat(InvitationEmail.of(email).value()).isEqualTo(email.substring(0, 180));
  }

  @Test
  @DisplayName("SC-BOARD-031 — casse différente → même e-mail normalisé (minuscules)")
  void caseInsensitive() {
    String ok = "x".repeat(60) + "@exemple.test";
    assertThat(InvitationEmail.of(ok.toUpperCase(java.util.Locale.ROOT)).value()).isEqualTo(ok);
    assertThat(InvitationEmail.of(" Nouveau@Exemple.test ").value())
        .isEqualTo("nouveau@exemple.test");
  }

  @Test
  @DisplayName(
      "SC-BOARD-011 — non-ASCII mis en minuscules puis refusé (FILTER_VALIDATE_EMAIL sans drapeau Unicode)")
  void nonAscii() {
    assertThatThrownBy(() -> InvitationEmail.of("ÉLODIE@exemple.test"))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Adresse e-mail invalide.");
  }
}
