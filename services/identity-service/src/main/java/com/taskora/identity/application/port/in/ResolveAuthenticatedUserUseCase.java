package com.taskora.identity.application.port.in;

/** Résolution d'un jeton en utilisateur authentifié (lève TokenRejectedException). */
public interface ResolveAuthenticatedUserUseCase {
  AuthenticatedUser resolve(String token);
}
