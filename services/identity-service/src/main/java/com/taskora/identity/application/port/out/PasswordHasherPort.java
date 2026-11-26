package com.taskora.identity.application.port.out;

import com.taskora.identity.domain.model.PlainPassword;
import com.taskora.identity.domain.service.PasswordHashing;

/** Hachage compatible Symfony. */
public interface PasswordHasherPort extends PasswordHashing {
  /** Vérification factice de temps comparable (e-mail inconnu). */
  void verifyAgainstDummy(PlainPassword password);
}
