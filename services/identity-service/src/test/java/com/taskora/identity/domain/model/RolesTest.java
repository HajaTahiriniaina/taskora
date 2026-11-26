package com.taskora.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RolesTest {

  @Test
  @DisplayName("SC-IDENT-010 — rôles stockés vides → [ROLE_USER]")
  void emptyGivesRoleUser() {
    assertThat(new Roles(List.of()).effective()).containsExactly("ROLE_USER");
  }

  @Test
  @DisplayName("SC-IDENT-010 — ROLE_USER ajouté après les rôles stockés, sans doublon (ordre de User::getRoles())")
  void appendsWithoutDuplicate() {
    assertThat(new Roles(List.of("ROLE_ADMIN")).effective()).containsExactly("ROLE_ADMIN", "ROLE_USER");
    assertThat(new Roles(List.of("ROLE_USER", "ROLE_ADMIN")).effective()).containsExactly("ROLE_USER", "ROLE_ADMIN");
    assertThat(new Roles(List.of("ROLE_X", "ROLE_X")).effective()).containsExactly("ROLE_X", "ROLE_USER");
  }

  @Test
  @DisplayName("SC-IDENT-010 — les rôles stockés ne sont pas modifiés")
  void storedUnchanged() {
    Roles r = new Roles(List.of());
    r.effective();
    assertThat(r.stored()).isEmpty();
  }
}
