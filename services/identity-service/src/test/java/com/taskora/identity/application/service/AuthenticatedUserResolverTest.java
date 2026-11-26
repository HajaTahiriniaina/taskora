package com.taskora.identity.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.taskora.identity.application.port.in.AuthenticatedUser;
import com.taskora.identity.application.port.out.TokenVerifierPort;
import com.taskora.identity.application.port.out.UserRepositoryPort;
import com.taskora.identity.application.port.out.VerifiedToken;
import com.taskora.identity.domain.exception.TokenRejectedException;
import com.taskora.identity.domain.exception.TokenRejection;
import com.taskora.identity.domain.model.UserId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuthenticatedUserResolverTest {
  private TokenVerifierPort verifier;
  private UserRepositoryPort users;
  private AuthenticatedUserResolver resolver;

  @BeforeEach
  void setUp() {
    verifier = mock(TokenVerifierPort.class);
    users = mock(UserRepositoryPort.class);
    resolver = new AuthenticatedUserResolver(verifier, users);
  }

  @Test
  @DisplayName(
      "SC-IDENT-014 — jeton valide → utilisateur rechargé par username (e-mail), rôles effectifs")
  void resolves() {
    when(verifier.verify("t")).thenReturn(new VerifiedToken("camille@exemple.fr", Map.of()));
    when(users.findByLoginEmail("camille@exemple.fr")).thenReturn(Optional.of(Users.camille()));
    AuthenticatedUser u = resolver.resolve("t");
    assertThat(u.id()).isEqualTo(new UserId(4));
    assertThat(u.email()).isEqualTo("camille@exemple.fr");
    assertThat(u.roles()).containsExactly("ROLE_USER");
  }

  @Test
  @DisplayName("SC-IDENT-013 — compte disparu → TokenRejectedException(ACCOUNT_NOT_FOUND)")
  void accountGone() {
    when(verifier.verify("t")).thenReturn(new VerifiedToken("parti@exemple.fr", Map.of()));
    when(users.findByLoginEmail(anyString())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> resolver.resolve("t"))
        .isInstanceOfSatisfying(
            TokenRejectedException.class,
            e -> assertThat(e.reason()).isEqualTo(TokenRejection.ACCOUNT_NOT_FOUND));
    verify(users).findByLoginEmail("parti@exemple.fr");
  }

  @Test
  @DisplayName(
      "SC-IDENT-012 — username absent → TokenRejectedException(MISSING_USERNAME) sans requête")
  void missingUsername() {
    Map<String, Object> claims = new HashMap<>();
    when(verifier.verify("t")).thenReturn(new VerifiedToken(null, claims));
    assertThatThrownBy(() -> resolver.resolve("t"))
        .isInstanceOfSatisfying(
            TokenRejectedException.class,
            e -> assertThat(e.reason()).isEqualTo(TokenRejection.MISSING_USERNAME));
    verifyNoInteractions(users);
  }

  @Test
  @DisplayName("SC-IDENT-012 — refus du vérificateur propagé tel quel (EXPIRED), sans requête")
  void verifierRejection() {
    when(verifier.verify("t")).thenThrow(new TokenRejectedException(TokenRejection.EXPIRED));
    assertThatThrownBy(() -> resolver.resolve("t"))
        .isInstanceOfSatisfying(
            TokenRejectedException.class,
            e -> assertThat(e.reason()).isEqualTo(TokenRejection.EXPIRED));
    verifyNoInteractions(users);
  }
}
