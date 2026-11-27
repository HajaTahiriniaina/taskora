package com.taskora.board.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskora.board.application.port.in.AuthenticatedUser;
import com.taskora.board.application.port.out.TokenVerifierPort;
import com.taskora.board.application.port.out.UserDirectoryPort;
import com.taskora.board.application.port.out.VerifiedToken;
import com.taskora.board.domain.exception.TokenRejectedException;
import com.taskora.board.domain.exception.TokenRejection;
import com.taskora.board.domain.model.UserId;
import com.taskora.board.domain.model.UserSummary;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * SC-BOARD-020 (B-ADR-2 point 3) — résolution de l'appelant : {@code uid} (identity) ou {@code
 * username} (Symfony, collation SQL), incohérence → {@code Invalid JWT Token}, compte disparu →
 * {@code Invalid credentials.}, ni l'un ni l'autre → message Lexik « username ». Ports simulés.
 */
class JwtResolutionTest {
  private static final UserSummary ALICE =
      new UserSummary(new UserId(1), "Alice Martin", "alice@exemple.test", "#6366f1");
  private static final UserSummary BOB =
      new UserSummary(new UserId(2), "Bruno Admin", "bruno@exemple.test", "#ef4444");

  /** Annuaire simulé : la recherche par e-mail imite utf8mb4_unicode_ci (casse, espaces finaux). */
  private static final class Directory implements UserDirectoryPort {
    private final List<UserSummary> users = List.of(ALICE, BOB);
    int byEmailCalls;

    @Override
    public Optional<UserSummary> findById(UserId id) {
      return users.stream().filter(u -> u.id().equals(id)).findFirst();
    }

    @Override
    public Optional<UserSummary> findByEmail(String email) {
      byEmailCalls++;
      String key = email.stripTrailing().toLowerCase(Locale.ROOT);
      return users.stream()
          .filter(u -> u.email().stripTrailing().toLowerCase(Locale.ROOT).equals(key))
          .findFirst();
    }
  }

  private static TokenVerifierPort verifier(Integer uid, String username) {
    return token -> new VerifiedToken(uid, username);
  }

  private static TokenVerifierPort rejecting(TokenRejection rejection) {
    return token -> {
      throw new TokenRejectedException(rejection);
    };
  }

  private static AuthenticatedUser resolve(TokenVerifierPort verifier) {
    return new AuthenticatedUserResolver(verifier, new Directory()).resolve("jeton");
  }

  private static void assertRejected(TokenVerifierPort verifier, TokenRejection expected) {
    assertThatThrownBy(() -> resolve(verifier))
        .isInstanceOfSatisfying(
            TokenRejectedException.class, e -> assertThat(e.rejection()).isEqualTo(expected))
        .hasMessage(expected.message());
  }

  @Test
  @DisplayName("SC-BOARD-020 — jeton identity (uid + username cohérents) → compte par uid")
  void identityToken() {
    AuthenticatedUser u = resolve(verifier(1, "alice@exemple.test"));
    assertThat(u.id()).isEqualTo(new UserId(1));
    assertThat(u.email()).isEqualTo("alice@exemple.test");
  }

  @Test
  @DisplayName("SC-BOARD-020 — jeton Symfony (sans uid) → compte par username, même résultat")
  void symfonyToken() {
    assertThat(resolve(verifier(null, "alice@exemple.test")))
        .isEqualTo(resolve(verifier(1, "alice@exemple.test")));
  }

  @Test
  @DisplayName(
      "SC-BOARD-020 — username comparé avec la collation (casse, espaces finaux) ; e-mail relu en base")
  void collation() {
    AuthenticatedUser u = resolve(verifier(null, "ALICE@Exemple.test  "));
    assertThat(u.id()).isEqualTo(new UserId(1));
    assertThat(u.email())
        .as("relu dans taskora.user, jamais pris dans le jeton")
        .isEqualTo("alice@exemple.test");
    assertThat(resolve(verifier(1, "Alice@EXEMPLE.test")).id()).isEqualTo(new UserId(1));
  }

  @Test
  @DisplayName("SC-BOARD-020 — uid seul (sans username) → compte par uid")
  void uidOnly() {
    assertThat(resolve(verifier(2, null)).id()).isEqualTo(new UserId(2));
  }

  @Test
  @DisplayName("SC-BOARD-020 — uid et username incohérents → 401 Invalid JWT Token")
  void incoherent() {
    assertRejected(verifier(1, "bruno@exemple.test"), TokenRejection.INVALID);
    assertRejected(verifier(1, "inconnu@exemple.test"), TokenRejection.INVALID);
  }

  @Test
  @DisplayName("SC-BOARD-020 — compte disparu (uid ou username inconnu) → 401 Invalid credentials.")
  void unknownAccount() {
    assertRejected(verifier(42, null), TokenRejection.UNKNOWN_ACCOUNT);
    assertRejected(verifier(null, "disparu@exemple.test"), TokenRejection.UNKNOWN_ACCOUNT);
  }

  @Test
  @DisplayName(
      "SC-BOARD-020 — ni uid ni username → 401 Unable to find key \"username\" in the token payload.")
  void noIdentity() {
    assertRejected(verifier(null, null), TokenRejection.NO_USERNAME);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = TokenRejection.class,
      names = {"INVALID", "EXPIRED"})
  @DisplayName("SC-BOARD-020 — refus du vérificateur propagé tel quel (précédence Lexik)")
  void verifierRejection(TokenRejection rejection) {
    assertRejected(rejecting(rejection), rejection);
  }

  @Test
  @DisplayName("SC-BOARD-020 — messages Lexik exacts")
  void lexikMessages() {
    assertThat(TokenRejection.NOT_FOUND.message()).isEqualTo("JWT Token not found");
    assertThat(TokenRejection.INVALID.message()).isEqualTo("Invalid JWT Token");
    assertThat(TokenRejection.EXPIRED.message()).isEqualTo("Expired JWT Token");
    assertThat(TokenRejection.NO_USERNAME.message())
        .isEqualTo("Unable to find key \"username\" in the token payload.");
    assertThat(TokenRejection.UNKNOWN_ACCOUNT.message()).isEqualTo("Invalid credentials.");
  }
}
