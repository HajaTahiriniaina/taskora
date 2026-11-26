package com.taskora.identity.application.port.in;

/** Reprise des comptes Symfony (A14). */
public interface ImportLegacyUsersUseCase {
  ImportReport importAll();

  VerificationReport verify();
}
