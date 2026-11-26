package com.taskora.identity.application.port.out;

/** Vérification de JWT (lève TokenRejectedException). */
public interface TokenVerifierPort {
  VerifiedToken verify(String token);
}
