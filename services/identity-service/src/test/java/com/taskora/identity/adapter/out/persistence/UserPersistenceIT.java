package com.taskora.identity.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskora.identity.application.port.out.UnitOfWorkPort;
import com.taskora.identity.application.port.out.UserRepositoryPort;
import com.taskora.identity.domain.exception.EmailAlreadyUsedException;
import com.taskora.identity.domain.model.AvatarColor;
import com.taskora.identity.domain.model.Email;
import com.taskora.identity.domain.model.HashedPassword;
import com.taskora.identity.domain.model.NewUser;
import com.taskora.identity.domain.model.PersonName;
import com.taskora.identity.domain.model.Roles;
import com.taskora.identity.domain.model.User;
import com.taskora.identity.domain.model.UserId;
import com.taskora.identity.support.ApiTestBase;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/** Adapter de persistance : Flyway V1/V2, collation, unicité (A7, identity-migration.md §1). */
class UserPersistenceIT extends ApiTestBase {
  @Autowired UserRepositoryPort users;
  @Autowired UnitOfWorkPort uow;

  private static NewUser newUser(String email) {
    return new NewUser(
        Email.ofStored(email),
        PersonName.of("Camille"),
        new HashedPassword("$2y$04$abcdefghijklmnopqrstuuWj1z8m5eT8Yb8eF6l2hYxE6m8dGJQ2K"),
        AvatarColor.parse("#14b8a6"),
        new Roles(List.of()),
        LocalDateTime.of(2026, 11, 26, 10, 0));
  }

  private User insert(String email) {
    return uow.inTransaction(() -> users.insert(newUser(email)));
  }

  @Test
  @DisplayName("SC-IDENT-001 — Flyway : V1 et V2 appliquées avec succès dans le schéma identity")
  void flywayApplied() {
    List<Map<String, Object>> rows =
        query("SELECT version, success FROM identity.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
    assertThat(rows).extracting(r -> r.get("version")).containsExactly("1", "2");
    assertThat(rows).allSatisfy(r -> assertThat(((Number) r.get("success")).intValue()).isEqualTo(1));
  }

  @Test
  @DisplayName("SC-IDENT-001 — colonnes : email en utf8mb4_unicode_ci (VARCHAR 180), password_hash en utf8mb4_bin")
  void columns() {
    List<Map<String, Object>> cols =
        query("SELECT column_name AS c, collation_name AS k, character_maximum_length AS l FROM information_schema.columns "
            + "WHERE table_schema = 'identity' AND table_name = 'user_account'");
    Map<Object, Map<String, Object>> byName = new java.util.HashMap<>();
    cols.forEach(c -> byName.put(c.get("c"), c));
    assertThat(byName).containsKeys("id", "email", "name", "password_hash", "avatar_color", "roles", "created_at", "updated_at");
    assertThat(byName.get("email").get("k")).isEqualTo("utf8mb4_unicode_ci");
    assertThat(((Number) byName.get("email").get("l")).intValue()).isEqualTo(180);
    assertThat(byName.get("password_hash").get("k")).isEqualTo("utf8mb4_bin");
    assertThat(count("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='identity' "
        + "AND table_name='user_account' AND index_name='uq_user_account_email' AND non_unique=0")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='identity' AND table_name='outbox_message'"))
        .isEqualTo(1);
  }

  @ParameterizedTest(name = "« {0} » trouvé")
  @ValueSource(strings = {"camille@exemple.fr", "CAMILLE@Exemple.FR", "camille@exemple.fr   ", "cämille@exemplé.fr"})
  @DisplayName("SC-IDENT-007 — findByLoginEmail suit la collation : casse, accents et espaces finaux ignorés")
  void collationLookup(String raw) {
    insert("camille@exemple.fr");
    assertThat(users.findByLoginEmail(raw)).isPresent().get().extracting(u -> u.email().value()).isEqualTo("camille@exemple.fr");
  }

  @Test
  @DisplayName("SC-IDENT-007 — espace initial significatif : non trouvé")
  void leadingSpace() {
    insert("camille@exemple.fr");
    assertThat(users.findByLoginEmail(" camille@exemple.fr")).isEmpty();
  }

  @Test
  @DisplayName("SC-IDENT-002 — index unique avec la collation : « É@x.fr » après « e@x.fr » → EmailAlreadyUsedException")
  void uniqueIndexCollation() {
    insert("e@x.fr");
    assertThatThrownBy(() -> insert("É@x.fr")).isInstanceOf(EmailAlreadyUsedException.class);
    assertThat(count("SELECT COUNT(*) FROM identity.user_account")).isEqualTo(1);
  }

  @Test
  @DisplayName("SC-IDENT-002 — existsByEmail insensible à la casse")
  void exists() {
    insert("camille@exemple.fr");
    assertThat(users.existsByEmail(Email.ofStored("CAMILLE@exemple.fr"))).isTrue();
    assertThat(users.existsByEmail(Email.ofStored("autre@exemple.fr"))).isFalse();
  }

  @Test
  @DisplayName("SC-IDENT-001 — insert puis findById : tous les champs relus, roles = []")
  void roundTrip() {
    User u = insert("camille@exemple.fr");
    User back = users.findById(u.id()).orElseThrow();
    assertThat(back.email().value()).isEqualTo("camille@exemple.fr");
    assertThat(back.name().value()).isEqualTo("Camille");
    assertThat(back.password().value()).startsWith("$2y$04$");
    assertThat(back.avatarColor().value()).isEqualTo("#14b8a6");
    assertThat(back.roles().stored()).isEmpty();
    assertThat(back.createdAt()).isEqualTo(LocalDateTime.of(2026, 11, 26, 10, 0));
    assertThat(users.findById(new UserId(9999))).isEmpty();
  }
}
