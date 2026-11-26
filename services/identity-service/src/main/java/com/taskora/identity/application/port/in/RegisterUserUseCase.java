package com.taskora.identity.application.port.in;

/** Inscription. */
public interface RegisterUserUseCase {
  AuthResult register(RegisterUserCommand command);
}
