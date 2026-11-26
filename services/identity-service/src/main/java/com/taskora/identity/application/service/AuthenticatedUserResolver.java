package com.taskora.identity.application.service;

import com.taskora.identity.application.port.in.AuthenticatedUser;
import com.taskora.identity.application.port.in.ResolveAuthenticatedUserUseCase;
import com.taskora.identity.application.port.out.TokenVerifierPort;
import com.taskora.identity.application.port.out.UserRepositoryPort;

/** Jeton → compte existant (SC-IDENT-012, 013). */
public class AuthenticatedUserResolver implements ResolveAuthenticatedUserUseCase {

  public AuthenticatedUserResolver(TokenVerifierPort verifier, UserRepositoryPort users) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  @Override
  public AuthenticatedUser resolve(String token) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
