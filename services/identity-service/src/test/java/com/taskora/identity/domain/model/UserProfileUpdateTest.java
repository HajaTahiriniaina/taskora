package com.taskora.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskora.identity.domain.exception.InvalidCurrentPasswordException;
import com.taskora.identity.domain.exception.PasswordTooLongException;
import com.taskora.identity.domain.exception.ValidationException;
import com.taskora.identity.domain.service.PasswordHashing;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UserProfileUpdateTest {
  private static final String TOO_SHORT = "Le mot de passe doit contenir au moins 8 caractères.";

  /** Hachage factice déterministe ; compte les appels de hachage. */
  static final class FakeHashing implements PasswordHashing {
    int hashCalls;

    @Override
    public HashedPassword hash(PlainPassword password) {
      hashCalls++;
      return new HashedPassword("H:" + password.value());
    }

    @Override
    public boolean matches(PlainPassword password, HashedPassword hash) {
      return hash.value().equals("H:" + password.value());
    }
  }

  private final FakeHashing hashing = new FakeHashing();

  private static User camille() {
    return User.rehydrate(
        new UserId(4),
        Email.ofStored("camille@exemple.fr"),
        PersonName.of("Camille"),
        new HashedPassword("H:ancienmdp1"),
        AvatarColor.parse("#14b8a6"),
        new Roles(List.of()),
        LocalDateTime.of(2026, 11, 26, 10, 0));
  }

  private static ProfileUpdate update(String name, String color, String current, String newPw) {
    return new ProfileUpdate(
        Optional.ofNullable(name),
        Optional.ofNullable(color),
        Optional.ofNullable(current),
        Optional.ofNullable(newPw));
  }

  private static void assertUnchanged(User u) {
    assertThat(u.name().value()).isEqualTo("Camille");
    assertThat(u.avatarColor().value()).isEqualTo("#14b8a6");
    assertThat(u.password().value()).isEqualTo("H:ancienmdp1");
    assertThat(u.email().value()).isEqualTo("camille@exemple.fr");
  }

  @Test
  @DisplayName("SC-IDENT-015 — le nom est trimé et devient « Camille M. » ; changedFields = [name]")
  void renames() {
    ProfileUpdateResult r = camille().updateProfile(update("  Camille M. ", null, null, null), hashing);
    assertThat(r.user().name().value()).isEqualTo("Camille M.");
    assertThat(r.changedFields()).containsExactly("name");
    assertThat(r.passwordChanged()).isFalse();
    assertThat(hashing.hashCalls).isZero();
  }

  @Test
  @DisplayName("SC-IDENT-015 — nom identique : aucun champ changé")
  void sameName() {
    ProfileUpdateResult r = camille().updateProfile(update(" Camille ", null, null, null), hashing);
    assertThat(r.changedFields()).isEmpty();
  }

  @Test
  @DisplayName("SC-IDENT-015 — nom vide → « Le champ \"name\" est obligatoire. »")
  void emptyName() {
    User u = camille();
    assertThatThrownBy(() -> u.updateProfile(update("  ", null, null, null), hashing))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Le champ \"name\" est obligatoire.");
    assertUnchanged(u);
  }

  @Test
  @DisplayName("SC-IDENT-015 — 150 caractères → tronqué à 120")
  void longName() {
    assertThat(camille().updateProfile(update("n".repeat(150), null, null, null), hashing).user().name().value())
        .hasSize(120);
  }

  @Test
  @DisplayName("SC-IDENT-016 — couleur enregistrée telle quelle ; changedFields = [avatarColor]")
  void color() {
    ProfileUpdateResult r = camille().updateProfile(update(null, "#0EA5E9", null, null), hashing);
    assertThat(r.user().avatarColor().value()).isEqualTo("#0EA5E9");
    assertThat(r.changedFields()).containsExactly("avatarColor");
  }

  @Test
  @DisplayName("SC-IDENT-015/016 — nom et couleur : changedFields dans l'ordre name, avatarColor")
  void nameAndColor() {
    ProfileUpdateResult r = camille().updateProfile(update("Camille M.", "#0EA5E9", null, null), hashing);
    assertThat(r.changedFields()).containsExactly("name", "avatarColor");
  }

  @ParameterizedTest
  @ValueSource(strings = {"red", "#12345", "#1234567", "", "#0EA5E9\n", " #0EA5E9", "#GGGGGG"})
  @DisplayName("SC-IDENT-031 E18 — couleur invalide → 400 « Couleur d'avatar invalide. », nom envoyé non appliqué")
  void invalidColorRejectsEverything(String color) {
    User u = camille();
    assertThatThrownBy(() -> u.updateProfile(update("Camille M.", color, null, null), hashing))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Couleur d'avatar invalide.");
    assertUnchanged(u);
  }

  @Test
  @DisplayName("SC-IDENT-031 E18 — ordre : nom vide contrôlé avant la couleur")
  void nameCheckedBeforeColor() {
    assertThatThrownBy(() -> camille().updateProfile(update("", "red", null, null), hashing))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Le champ \"name\" est obligatoire.");
  }

  @Test
  @DisplayName("SC-IDENT-031 E18 — ordre : couleur contrôlée avant le mot de passe")
  void colorCheckedBeforePassword() {
    assertThatThrownBy(() -> camille().updateProfile(update(null, "red", "faux", "nouveaumdp1"), hashing))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Couleur d'avatar invalide.");
  }

  @Test
  @DisplayName("SC-IDENT-017 — changement de mot de passe : nouveau hash, passwordChanged, aucun champ public changé")
  void changesPassword() {
    ProfileUpdateResult r = camille().updateProfile(update(null, null, "ancienmdp1", "nouveaumdp1"), hashing);
    assertThat(r.passwordChanged()).isTrue();
    assertThat(r.user().password().value()).isEqualTo("H:nouveaumdp1");
    assertThat(r.changedFields()).isEmpty();
  }

  @Test
  @DisplayName("SC-IDENT-018 — mot de passe actuel faux → InvalidCurrentPasswordException, rien n'est modifié")
  void wrongCurrent() {
    User u = camille();
    assertThatThrownBy(() -> u.updateProfile(update(null, null, "faux", "nouveaumdp1"), hashing))
        .isInstanceOf(InvalidCurrentPasswordException.class);
    assertUnchanged(u);
    assertThat(hashing.hashCalls).isZero();
  }

  @Test
  @DisplayName("SC-IDENT-018 — mot de passe actuel absent avec newPassword → InvalidCurrentPasswordException")
  void missingCurrent() {
    assertThatThrownBy(() -> camille().updateProfile(update(null, null, null, "nouveaumdp1"), hashing))
        .isInstanceOf(InvalidCurrentPasswordException.class);
  }

  @Test
  @DisplayName("SC-IDENT-018 — bon mot de passe actuel, nouveau de 7 caractères → message de longueur")
  void newTooShort() {
    User u = camille();
    assertThatThrownBy(() -> u.updateProfile(update(null, null, "ancienmdp1", "1234567"), hashing))
        .isInstanceOf(ValidationException.class)
        .hasMessage(TOO_SHORT);
    assertUnchanged(u);
  }

  @Test
  @DisplayName("SC-IDENT-018 — ordre : mot de passe actuel vérifié avant la longueur du nouveau")
  void currentBeforeLength() {
    assertThatThrownBy(() -> camille().updateProfile(update(null, null, "faux", "123"), hashing))
        .isInstanceOf(InvalidCurrentPasswordException.class);
  }

  @Test
  @DisplayName("SC-IDENT-018 — newPassword absent : aucune vérification ni hachage, même si currentPassword est faux")
  void noNewPassword() {
    ProfileUpdateResult r = camille().updateProfile(update(null, null, "faux", null), hashing);
    assertThat(r.passwordChanged()).isFalse();
    assertThat(r.user().password().value()).isEqualTo("H:ancienmdp1");
    assertThat(hashing.hashCalls).isZero();
  }

  @Test
  @DisplayName("SC-IDENT-028 E15 — newPassword de 4097 octets avec bon mot de passe actuel → PasswordTooLongException")
  void newTooLong() {
    User u = camille();
    assertThatThrownBy(() -> u.updateProfile(update(null, null, "ancienmdp1", "a".repeat(4097)), hashing))
        .isInstanceOf(PasswordTooLongException.class);
    assertUnchanged(u);
    assertThat(hashing.hashCalls).isZero();
  }

  @Test
  @DisplayName("SC-IDENT-028 E15 — ordre : mot de passe actuel faux l'emporte sur la longueur maximale")
  void currentBeforeMaxLength() {
    assertThatThrownBy(() -> camille().updateProfile(update(null, null, "faux", "a".repeat(4097)), hashing))
        .isInstanceOf(InvalidCurrentPasswordException.class);
  }

  @Test
  @DisplayName("SC-IDENT-034 E21 — newPassword \"0\" avec bon mot de passe actuel → message de longueur")
  void zeroIsARealPasswordTooShort() {
    assertThatThrownBy(() -> camille().updateProfile(update(null, null, "ancienmdp1", "0"), hashing))
        .isInstanceOf(ValidationException.class)
        .hasMessage(TOO_SHORT);
  }

  @Test
  @DisplayName("SC-IDENT-034 E21 — newPassword \"0\" sans mot de passe actuel → InvalidCurrentPasswordException")
  void zeroRequiresCurrent() {
    assertThatThrownBy(() -> camille().updateProfile(update(null, null, null, "0"), hashing))
        .isInstanceOf(InvalidCurrentPasswordException.class);
  }

  @Test
  @DisplayName("SC-IDENT-019 — erreur sur le mot de passe : le nom envoyé en même temps n'est pas appliqué")
  void atomic() {
    User u = camille();
    assertThatThrownBy(() -> u.updateProfile(update("Camille M.", "#0EA5E9", "faux", "nouveaumdp1"), hashing))
        .isInstanceOf(InvalidCurrentPasswordException.class);
    assertUnchanged(u);
  }

  @Test
  @DisplayName("SC-IDENT-019 — succès : nouvel état produit, l'objet d'origine n'est pas modifié")
  void producesNewState() {
    User u = camille();
    ProfileUpdateResult r = u.updateProfile(update("Camille M.", "#0EA5E9", "ancienmdp1", "nouveaumdp1"), hashing);
    assertThat(r.user()).isNotSameAs(u);
    assertUnchanged(u);
    assertThat(r.user().id()).isEqualTo(new UserId(4));
    assertThat(r.user().createdAt()).isEqualTo(u.createdAt());
  }

  @Test
  @DisplayName("SC-IDENT-020 — l'e-mail n'est jamais modifié par une mise à jour de profil")
  void emailNeverChanges() {
    ProfileUpdateResult r =
        camille().updateProfile(update("Camille M.", "#0EA5E9", "ancienmdp1", "nouveaumdp1"), hashing);
    assertThat(r.user().email().value()).isEqualTo("camille@exemple.fr");
    assertThat(ProfileUpdate.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .containsExactly("name", "avatarColor", "currentPassword", "newPassword");
  }
}
