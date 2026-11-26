package com.taskora.identity.application.service;

import com.taskora.identity.application.port.in.AuthResult;
import com.taskora.identity.application.port.in.AuthenticateUseCase;
import com.taskora.identity.application.port.in.LoginCommand;
import com.taskora.identity.application.port.out.PasswordHasherPort;
import com.taskora.identity.application.port.out.TokenIssuerPort;
import com.taskora.identity.application.port.out.UserRepositoryPort;

/** Connexion. */
public class AuthenticateService implements AuthenticateUseCase {

  public AuthenticateService(
      UserRepositoryPort users, PasswordHasherPort hasher, TokenIssuerPort tokens) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  @Override
  public AuthResult authenticate(LoginCommand command) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
