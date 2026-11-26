package com.taskora.identity.application.port.in;

/** Connexion. */
public interface AuthenticateUseCase {
  AuthResult authenticate(LoginCommand command);
}
