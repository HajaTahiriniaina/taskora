package com.taskora.board.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskora.board.domain.exception.ForbiddenException;
import com.taskora.board.domain.exception.ValidationException;
import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.Membership;
import com.taskora.board.domain.model.Role;
import com.taskora.board.domain.model.UserId;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** SC-BOARD-009, 014, 015, 016, 032 — règles d'adhésion (propriétaire unique, rôles). */
class MembershipRulesTest {
  private static final BoardId BOARD = new BoardId(12);
  private static final UserId ALICE = new UserId(1);
  private static final UserId BOB = new UserId(2);
  private static final UserId CARLA = new UserId(3);

  private static Membership membership(UserId user, Role role) {
    return new Membership(BOARD, user, role, false, Instant.parse("2026-11-20T09:00:00Z"));
  }

  @Test
  @DisplayName("SC-BOARD-009 — ajout : admin et member conservés")
  void additionKeepsAdminAndMember() {
    assertThat(MembershipRules.roleForAddition("admin")).isEqualTo(Role.ADMIN);
    assertThat(MembershipRules.roleForAddition("member")).isEqualTo(Role.MEMBER);
  }

  @ParameterizedTest(name = "\"{0}\" → member")
  @NullAndEmptySource
  @ValueSource(strings = {"owner", "Admin", "ADMIN", "superadmin", " admin"})
  @DisplayName("SC-BOARD-032 — ajout : owner, valeur inconnue, absente → member")
  void additionDefaultsToMember(String requested) {
    assertThat(MembershipRules.roleForAddition(requested)).isEqualTo(Role.MEMBER);
  }

  @Test
  @DisplayName("SC-BOARD-014 — changement : admin et member acceptés")
  void changeAccepted() {
    assertThat(MembershipRules.roleForChange("admin")).isEqualTo(Role.ADMIN);
    assertThat(MembershipRules.roleForChange("member")).isEqualTo(Role.MEMBER);
  }

  @ParameterizedTest(name = "\"{0}\" → 400")
  @NullAndEmptySource
  @ValueSource(strings = {"owner", "Admin", "superadmin", " member"})
  @DisplayName("SC-BOARD-014 / 032 — changement : owner, vide, inconnu → 400 Rôle invalide.")
  void changeRefused(String requested) {
    assertThatThrownBy(() -> MembershipRules.roleForChange(requested))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Rôle invalide.");
  }

  @Test
  @DisplayName("SC-BOARD-014 — rôle du propriétaire non modifiable ; admin et member modifiables")
  void ownerRoleLocked() {
    assertThatThrownBy(() -> MembershipRules.ensureRoleChangeable(membership(ALICE, Role.OWNER)))
        .isInstanceOf(ForbiddenException.class)
        .hasMessage("Le rôle du propriétaire ne peut pas être modifié.");
    assertThatCode(() -> MembershipRules.ensureRoleChangeable(membership(BOB, Role.ADMIN)))
        .doesNotThrowAnyException();
    assertThatCode(() -> MembershipRules.ensureRoleChangeable(membership(CARLA, Role.MEMBER)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("SC-BOARD-016 — un membre peut quitter le tableau")
  void memberLeaves() {
    assertThatCode(() ->
            MembershipRules.ensureRemovable(CARLA, Role.MEMBER, membership(CARLA, Role.MEMBER)))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> MembershipRules.ensureRemovable(BOB, Role.ADMIN, membership(BOB, Role.ADMIN)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName(
      "SC-BOARD-010 — un simple membre ne retire pas autrui (même le propriétaire) : 403 gestion")
  void memberCannotRemoveOthers() {
    assertThatThrownBy(
            () -> MembershipRules.ensureRemovable(CARLA, Role.MEMBER, membership(BOB, Role.ADMIN)))
        .isInstanceOf(ForbiddenException.class)
        .hasMessage("Action réservée aux administrateurs du tableau.");
    assertThatThrownBy(() ->
            MembershipRules.ensureRemovable(CARLA, Role.MEMBER, membership(ALICE, Role.OWNER)))
        .isInstanceOf(ForbiddenException.class)
        .hasMessage("Action réservée aux administrateurs du tableau.");
  }

  @Test
  @DisplayName("SC-BOARD-015 — un gestionnaire retire un membre ou un admin")
  void managerRemoves() {
    assertThatCode(
            () -> MembershipRules.ensureRemovable(BOB, Role.ADMIN, membership(CARLA, Role.MEMBER)))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> MembershipRules.ensureRemovable(ALICE, Role.OWNER, membership(BOB, Role.ADMIN)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("SC-BOARD-015 / 032 — le propriétaire ne quitte pas et n'est pas retiré : 403")
  void ownerStays() {
    assertThatThrownBy(
            () -> MembershipRules.ensureRemovable(ALICE, Role.OWNER, membership(ALICE, Role.OWNER)))
        .isInstanceOf(ForbiddenException.class)
        .hasMessage("Le propriétaire ne peut pas quitter son tableau.");
    assertThatThrownBy(
            () -> MembershipRules.ensureRemovable(BOB, Role.ADMIN, membership(ALICE, Role.OWNER)))
        .isInstanceOf(ForbiddenException.class)
        .hasMessage("Le propriétaire ne peut pas quitter son tableau.");
  }
}
