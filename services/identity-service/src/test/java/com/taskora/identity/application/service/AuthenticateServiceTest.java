package com.taskora.identity.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.taskora.identity.application.port.in.AuthResult;
import com.taskora.identity.application.port.in.LoginCommand;
import com.taskora.identity.application.port.out.PasswordHasherPort;
import com.taskora.identity.application.port.out.TokenIssuerPort;
import com.taskora.identity.application.port.out.UserRepositoryPort;
import com.taskora.identity.domain.exception.InvalidCredentialsException;
import com.taskora.identity.domain.model.PlainPassword;
import com.taskora.identity.domain.model.User;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuthenticateServiceTest {
  private UserRepositoryPort users;
  private PasswordHasherPort hasher;
  private TokenIssuerPort tokens;
  private AuthenticateService service;

  @BeforeEach
  void setUp() {
    users = mock(UserRepositoryPort.class);
    hasher = mock(PasswordHasherPort.class);
    tokens = mock(TokenIssuerPort.class);
    service = new AuthenticateService(users, hasher, tokens);
    when(tokens.issue(any())).thenReturn("jeton.de.test");
  }

  @Test
  @DisplayName("SC-IDENT-007 — connexion nominale : jeton et profil")
  void nominal() {
    User camille = Users.camille();
    when(users.findByLoginEmail("camille@exemple.fr")).thenReturn(Optional.of(camille));
    when(hasher.matches(any(), any())).thenReturn(true);

    AuthResult r = service.authenticate(new LoginCommand("camille@exemple.fr", "motdepasse"));

    assertThat(r.token()).isEqualTo("jeton.de.test");
    assertThat(r.user().id()).isEqualTo(4);
    assertThat(r.user().email()).isEqualTo("camille@exemple.fr");
    verify(tokens).issue(camille);
    ArgumentCaptor<PlainPassword> pw = ArgumentCaptor.forClass(PlainPassword.class);
    verify(hasher).matches(pw.capture(), any());
    assertThat(pw.getValue().value()).isEqualTo("motdepasse");
  }

  @Test
  @DisplayName(
      "SC-IDENT-007 — l'e-mail est passé BRUT au dépôt (ni trim ni minuscules : la collation décide)")
  void rawEmail() {
    when(users.findByLoginEmail(anyString())).thenReturn(Optional.empty());
    assertThatThrownBy(
            () -> service.authenticate(new LoginCommand(" Camille@Exemple.fr ", "motdepasse")))
        .isInstanceOf(InvalidCredentialsException.class);
    verify(users).findByLoginEmail(" Camille@Exemple.fr ");
  }

  @Test
  @DisplayName(
      "SC-IDENT-008 — e-mail inconnu → vérification factice (temps comparable) puis InvalidCredentialsException")
  void unknownEmail() {
    when(users.findByLoginEmail(anyString())).thenReturn(Optional.empty());
    assertThatThrownBy(
            () -> service.authenticate(new LoginCommand("inconnu@exemple.fr", "motdepasse")))
        .isInstanceOf(InvalidCredentialsException.class);
    ArgumentCaptor<PlainPassword> pw = ArgumentCaptor.forClass(PlainPassword.class);
    verify(hasher).verifyAgainstDummy(pw.capture());
    assertThat(pw.getValue().value()).isEqualTo("motdepasse");
    verifyNoInteractions(tokens);
  }

  @Test
  @DisplayName(
      "SC-IDENT-008 — mauvais mot de passe → la même InvalidCredentialsException, aucun jeton")
  void wrongPassword() {
    when(users.findByLoginEmail(anyString())).thenReturn(Optional.of(Users.camille()));
    when(hasher.matches(any(), any())).thenReturn(false);
    assertThatThrownBy(
            () -> service.authenticate(new LoginCommand("camille@exemple.fr", "mauvais")))
        .isInstanceOf(InvalidCredentialsException.class);
    verify(hasher, never()).verifyAgainstDummy(any());
    verifyNoInteractions(tokens);
  }

  @Test
  @DisplayName("SC-IDENT-008 — e-mail de plus de 4096 octets → refus sans appel au dépôt")
  void emailTooLong() {
    assertThatThrownBy(() -> service.authenticate(new LoginCommand("a".repeat(4097), "motdepasse")))
        .isInstanceOf(InvalidCredentialsException.class);
    verifyNoInteractions(users, tokens);
  }

  @Test
  @DisplayName(
      "SC-IDENT-008 — mot de passe de plus de 4096 octets → refus (401) sans appel au dépôt")
  void passwordTooLong() {
    assertThatThrownBy(
            () -> service.authenticate(new LoginCommand("camille@exemple.fr", "a".repeat(4097))))
        .isInstanceOf(InvalidCredentialsException.class);
    verifyNoInteractions(users, tokens);
  }
}
