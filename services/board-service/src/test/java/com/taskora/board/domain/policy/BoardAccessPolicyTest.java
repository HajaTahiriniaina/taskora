package com.taskora.board.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskora.board.domain.exception.BoardInTrashException;
import com.taskora.board.domain.exception.BoardNotFoundException;
import com.taskora.board.domain.exception.ForbiddenException;
import com.taskora.board.domain.exception.NotInTrashException;
import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.Role;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** SC-BOARD-021 (politique) — toutes les branches de B-ADR-3 / spec §5.3. */
class BoardAccessPolicyTest {
  private static final BoardId BOARD = new BoardId(12);
  private static final Instant TRASHED_AT = Instant.parse("2026-11-25T08:30:00Z");
  private static final String DELETE_FORBIDDEN = "Seul le propriétaire peut supprimer le tableau.";

  private final BoardAccessPolicy policy = new BoardAccessPolicy();

  private static BoardAccessView open(Role role) {
    return new BoardAccessView(BOARD, true, null, role);
  }

  private static BoardAccessView trashed(Role role) {
    return new BoardAccessView(BOARD, true, TRASHED_AT, role);
  }

  @Nested
  @DisplayName("requireMember")
  class RequireMember {
    @Test
    @DisplayName("SC-BOARD-021 — tableau inexistant → 404 Tableau introuvable.")
    void missing() {
      assertThatThrownBy(() -> policy.requireMember(BoardAccessView.missing(BOARD)))
          .isInstanceOf(BoardNotFoundException.class)
          .hasMessage("Tableau introuvable.");
    }

    @Test
    @DisplayName("SC-BOARD-021 — non-membre d'un tableau ouvert → même 404 que l'inexistant")
    void nonMemberOpen() {
      assertThatThrownBy(() -> policy.requireMember(open(null)))
          .isInstanceOf(BoardNotFoundException.class)
          .hasMessage("Tableau introuvable.");
    }

    @Test
    @DisplayName(
        "SC-BOARD-021 — non-membre d'un tableau dans la corbeille → Tableau introuvable. (pas de fuite)")
    void nonMemberTrashed() {
      assertThatThrownBy(() -> policy.requireMember(trashed(null)))
          .isInstanceOf(BoardNotFoundException.class)
          .hasMessage("Tableau introuvable.");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Role.class)
    @DisplayName(
        "SC-BOARD-004 — membre d'un tableau dans la corbeille → Ce tableau est dans la corbeille.")
    void memberTrashed(Role role) {
      assertThatThrownBy(() -> policy.requireMember(trashed(role)))
          .isInstanceOf(BoardInTrashException.class)
          .hasMessage("Ce tableau est dans la corbeille.");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Role.class)
    @DisplayName("SC-BOARD-003 — membre d'un tableau ouvert → son rôle")
    void member(Role role) {
      assertThat(policy.requireMember(open(role))).isEqualTo(role);
    }
  }

  @Nested
  @DisplayName("requireManager")
  class RequireManager {
    @ParameterizedTest(name = "{0}")
    @EnumSource(
        value = Role.class,
        names = {"OWNER", "ADMIN"})
    @DisplayName("SC-BOARD-009 — owner et admin gèrent")
    void managers(Role role) {
      assertThat(policy.requireManager(open(role))).isEqualTo(role);
    }

    @Test
    @DisplayName(
        "SC-BOARD-010 — simple membre → 403 Action réservée aux administrateurs du tableau.")
    void member() {
      assertThatThrownBy(() -> policy.requireManager(open(Role.MEMBER)))
          .isInstanceOf(ForbiddenException.class)
          .hasMessage("Action réservée aux administrateurs du tableau.");
    }

    @Test
    @DisplayName("SC-BOARD-021 — non-membre → 404 avant le 403 (jamais de fuite)")
    void nonMember() {
      assertThatThrownBy(() -> policy.requireManager(open(null)))
          .isInstanceOf(BoardNotFoundException.class);
      assertThatThrownBy(() -> policy.requireManager(BoardAccessView.missing(BOARD)))
          .isInstanceOf(BoardNotFoundException.class);
    }

    @Test
    @DisplayName("SC-BOARD-004 — gestionnaire d'un tableau dans la corbeille → 404 corbeille")
    void managerTrashed() {
      assertThatThrownBy(() -> policy.requireManager(trashed(Role.OWNER)))
          .isInstanceOf(BoardInTrashException.class);
      assertThatThrownBy(() -> policy.requireManager(trashed(Role.MEMBER)))
          .as("corbeille avant le 403")
          .isInstanceOf(BoardInTrashException.class);
    }
  }

  @Nested
  @DisplayName("requireOwner")
  class RequireOwner {
    @Test
    @DisplayName("SC-BOARD-007 — propriétaire : accepté")
    void owner() {
      policy.requireOwner(open(Role.OWNER), DELETE_FORBIDDEN);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
        value = Role.class,
        names = {"ADMIN", "MEMBER"})
    @DisplayName("SC-BOARD-007 — autre membre → 403 avec le message fourni")
    void others(Role role) {
      assertThatThrownBy(() -> policy.requireOwner(open(role), DELETE_FORBIDDEN))
          .isInstanceOf(ForbiddenException.class)
          .hasMessage(DELETE_FORBIDDEN);
    }

    @Test
    @DisplayName(
        "SC-BOARD-021 — non-membre ou inexistant → 404 ; propriétaire et corbeille → 404 corbeille")
    void notFoundAndTrash() {
      assertThatThrownBy(() -> policy.requireOwner(open(null), DELETE_FORBIDDEN))
          .isInstanceOf(BoardNotFoundException.class);
      assertThatThrownBy(
              () -> policy.requireOwner(BoardAccessView.missing(BOARD), DELETE_FORBIDDEN))
          .isInstanceOf(BoardNotFoundException.class);
      assertThatThrownBy(() -> policy.requireOwner(trashed(Role.OWNER), DELETE_FORBIDDEN))
          .isInstanceOf(BoardInTrashException.class);
    }
  }

  @Nested
  @DisplayName("requireOwnerOfTrashedBoard")
  class RequireOwnerOfTrashedBoard {
    @Test
    @DisplayName("SC-TRASH-007 — propriétaire d'un tableau dans la corbeille : accepté")
    void owner() {
      policy.requireOwnerOfTrashedBoard(trashed(Role.OWNER));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
        value = Role.class,
        names = {"ADMIN", "MEMBER"})
    @DisplayName("SC-TRASH-007 — membre non propriétaire → 403 message actuel")
    void notOwner(Role role) {
      assertThatThrownBy(() -> policy.requireOwnerOfTrashedBoard(trashed(role)))
          .isInstanceOf(ForbiddenException.class)
          .hasMessage("Seul le propriétaire du tableau peut le restaurer ou le supprimer.");
    }

    @Test
    @DisplayName(
        "SC-BOARD-034 — absent de la corbeille, inexistant ou non-membre → 404 Ce tableau n’est pas dans la corbeille.")
    void notInTrash() {
      for (BoardAccessView v :
          new BoardAccessView[] {
            BoardAccessView.missing(BOARD),
            open(Role.OWNER),
            open(Role.MEMBER),
            trashed(null),
            open(null)
          }) {
        assertThatThrownBy(() -> policy.requireOwnerOfTrashedBoard(v))
            .as("%s", v)
            .isInstanceOf(NotInTrashException.class)
            .hasMessage("Ce tableau n’est pas dans la corbeille.");
      }
    }
  }
}
