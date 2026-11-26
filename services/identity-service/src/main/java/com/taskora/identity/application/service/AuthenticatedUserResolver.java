package com.taskora.identity.application.service;

import com.taskora.identity.application.port.in.AuthenticatedUser;
import com.taskora.identity.application.port.in.ResolveAuthenticatedUserUseCase;
import com.taskora.identity.application.port.out.TokenVerifierPort;
import com.taskora.identity.application.port.out.UserRepositoryPort;
import com.taskora.identity.application.port.out.VerifiedToken;
import com.taskora.identity.domain.exception.TokenRejectedException;
import com.taskora.identity.domain.exception.TokenRejection;
import com.taskora.identity.domain.model.User;

/** Jeton → compte existant (SC-IDENT-012, 013). */
public class AuthenticatedUserResolver implements ResolveAuthenticatedUserUseCase {
  private final TokenVerifierPort verifier;
  private final UserRepositoryPort users;

  public AuthenticatedUserResolver(TokenVerifierPort verifier, UserRepositoryPort users) {
    this.verifier = verifier;
    this.users = users;
  }

  @Override
  public AuthenticatedUser resolve(String token) {
    VerifiedToken verified = verifier.verify(token);
    if (verified.username() == null) {
      throw new TokenRejectedException(TokenRejection.MISSING_USERNAME);
    }
    User user =
        users
            .findByLoginEmail(verified.username())
            .orElseThrow(() -> new TokenRejectedException(TokenRejection.ACCOUNT_NOT_FOUND));
    return new AuthenticatedUser(user.id(), user.email().value(), user.roles().effective());
  }
}
