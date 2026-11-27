package com.taskora.board.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.domain.model.DefaultBoardContent.DefaultLabel;
import com.taskora.board.domain.model.DefaultBoardContent.DefaultList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SC-BOARD-001 — contenu par défaut exact (BoardController::create de Symfony). */
class DefaultBoardContentTest {

  @Test
  @DisplayName("SC-BOARD-001 — 4 listes À faire, En cours, En revue, Terminé aux positions 0 à 3")
  void lists() {
    assertThat(DefaultBoardContent.lists())
        .containsExactly(
            new DefaultList("À faire", 0),
            new DefaultList("En cours", 1),
            new DefaultList("En revue", 2),
            new DefaultList("Terminé", 3));
  }

  @Test
  @DisplayName("SC-BOARD-001 — 6 étiquettes, noms et couleurs exacts, dans l'ordre")
  void labels() {
    assertThat(DefaultBoardContent.labels())
        .containsExactly(
            new DefaultLabel("Urgent", "#ef4444"),
            new DefaultLabel("Bug", "#f97316"),
            new DefaultLabel("Feature", "#6366f1"),
            new DefaultLabel("Design", "#ec4899"),
            new DefaultLabel("Backend", "#14b8a6"),
            new DefaultLabel("Idée", "#eab308"));
  }

  @Test
  @DisplayName("SC-BOARD-001 — fond par défaut aurora ; valeur hors liste ignorée ; dix valeurs")
  void backgrounds() {
    assertThat(Background.values()).hasSize(10);
    assertThat(Background.parse("ocean")).contains(Background.OCEAN);
    assertThat(Background.parse("rainbow")).isEmpty();
    assertThat(Background.parse("Ocean")).as("sensible à la casse (in_array strict)").isEmpty();
    assertThat(Background.parse(null)).isEmpty();
    assertThat(Background.AURORA.value()).isEqualTo("aurora");
    assertThat(Background.fromStored("graphite")).isEqualTo(Background.GRAPHITE);
  }

  @Test
  @DisplayName("SC-BOARD-001 — titre : trim PHP, obligatoire, 160 points de code")
  void title() {
    assertThat(BoardTitle.of(" \t\n\r\0\u000BSprint 12 \0").value()).isEqualTo("Sprint 12");
    String emoji = "🚀".repeat(170);
    assertThat(BoardTitle.of(emoji).value()).isEqualTo("🚀".repeat(160));
    assertThat(BoardTitle.of("é".repeat(200)).value()).hasSize(160);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> BoardTitle.of(" \t "))
        .isInstanceOf(com.taskora.board.domain.exception.ValidationException.class)
        .hasMessage("Le champ \"title\" est obligatoire.");
  }
}
