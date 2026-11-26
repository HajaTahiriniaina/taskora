package com.taskora.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskora.identity.domain.exception.PasswordTooLongException;
import com.taskora.identity.domain.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlainPasswordTest {
  private static final String TOO_SHORT = "Le mot de passe doit contenir au moins 8 caractères.";

  @Test
  @DisplayName("SC-IDENT-003/018 — 7 caractères → refus")
  void sevenRejected() {
    assertThatThrownBy(() -> PlainPassword.of("1234567").requireMinimumLength())
        .isInstanceOf(ValidationException.class)
        .hasMessage(TOO_SHORT);
  }

  @Test
  @DisplayName("SC-IDENT-003 — 8 caractères exactement → accepté")
  void eightAccepted() {
    assertThatCode(() -> PlainPassword.of("12345678").requireMinimumLength())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("SC-IDENT-003 — longueur en points de code : ééééééé (7, 14 octets) refusé")
  void accentsCountedAsCodePoints() {
    PlainPassword p = PlainPassword.of("ééééééé");
    assertThat(p.codePointLength()).isEqualTo(7);
    assertThat(p.utf8Length()).isEqualTo(14);
    assertThatThrownBy(p::requireMinimumLength)
        .isInstanceOf(ValidationException.class)
        .hasMessage(TOO_SHORT);
  }

  @Test
  @DisplayName("SC-IDENT-003 — emoji : 4 × 🔑 + 4 lettres = 8 points de code → accepté")
  void emojiCountsAsOne() {
    PlainPassword p = PlainPassword.of("🔑🔑🔑🔑abcd");
    assertThat(p.codePointLength()).isEqualTo(8);
    assertThatCode(p::requireMinimumLength).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("SC-IDENT-003 — vide")
  void empty() {
    assertThat(PlainPassword.of("").isEmpty()).isTrue();
    assertThat(PlainPassword.of("x").isEmpty()).isFalse();
    assertThatThrownBy(() -> PlainPassword.of("").requireMinimumLength())
        .isInstanceOf(ValidationException.class);
  }

  @Test
  @DisplayName("SC-IDENT-028 E15 — 4096 octets exactement → accepté")
  void max4096Accepted() {
    assertThatCode(() -> PlainPassword.of("a".repeat(4096)).requireMaximumLength())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("SC-IDENT-028 E15 — 4097 octets → PasswordTooLongException")
  void over4096Rejected() {
    assertThatThrownBy(() -> PlainPassword.of("a".repeat(4097)).requireMaximumLength())
        .isInstanceOf(PasswordTooLongException.class);
  }

  @Test
  @DisplayName(
      "SC-IDENT-028 E15 — limite en OCTETS : 2049 × é = 4098 octets → refus ; 2048 × é = 4096 → accepté")
  void limitInBytes() {
    assertThatThrownBy(() -> PlainPassword.of("é".repeat(2049)).requireMaximumLength())
        .isInstanceOf(PasswordTooLongException.class);
    assertThatCode(() -> PlainPassword.of("é".repeat(2048)).requireMaximumLength())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("SC-IDENT-024 — toString ne contient jamais le mot de passe")
  void toStringMasked() {
    PlainPassword p = PlainPassword.of("motdepasse-secret");
    assertThat(p.toString()).doesNotContain("motdepasse-secret").doesNotContain("secret");
    assertThat(p.value()).isEqualTo("motdepasse-secret");
  }

  @Test
  @DisplayName("SC-IDENT-024 — HashedPassword.toString masqué")
  void hashToStringMasked() {
    assertThat(new HashedPassword("$2y$13$abcdefghijklmnopqrstuv").toString())
        .isEqualTo("HashedPassword[***]");
  }
}
