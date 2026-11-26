package com.taskora.identity.application.service;

import com.taskora.identity.application.port.in.AuthResult;
import com.taskora.identity.application.port.in.AuthenticateUseCase;
import com.taskora.identity.application.port.in.LoginCommand;
import com.taskora.identity.application.port.out.PasswordHasherPort;
import com.taskora.identity.application.port.out.TokenIssuerPort;
import com.taskora.identity.application.port.out.UserRepositoryPort;
import com.taskora.identity.domain.exception.InvalidCredentialsException;
import com.taskora.identity.domain.model.PlainPassword;
import com.taskora.identity.domain.model.User;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/** Connexion. */
public class AuthenticateService implements AuthenticateUseCase {
  private final UserRepositoryPort users;
  private final PasswordHasherPort hasher;
  private final TokenIssuerPort tokens;

  public AuthenticateService(
      UserRepositoryPort users, PasswordHasherPort hasher, TokenIssuerPort tokens) {
    this.users = users;
    this.hasher = hasher;
    this.tokens = tokens;
  }

  @Override
  public AuthResult authenticate(LoginCommand command) {
    // Le parseur web garantit des chaînes non nulles ; "" ne trouve aucun compte.
    String email = Objects.requireNonNullElse(command.email(), "");
    String rawPassword = Objects.requireNonNullElse(command.password(), "");
    if (tooLong(email) || tooLong(rawPassword)) {
      throw new InvalidCredentialsException();
    }
    PlainPassword password = PlainPassword.of(rawPassword);
    // L'e-mail est cherché tel que saisi : la collation de la colonne décide (A7).
    Optional<User> found = users.findByLoginEmail(email);
    if (found.isEmpty()) {
      hasher.verifyAgainstDummy(password);
      throw new InvalidCredentialsException();
    }
    User user = found.get();
    if (!hasher.matches(password, user.password())) {
      throw new InvalidCredentialsException();
    }
    return new AuthResult(tokens.issue(user), UserViews.of(user));
  }

  private static boolean tooLong(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length > PlainPassword.MAX_BYTES;
  }
}
