package com.taskora.identity.adapter.out.security;

import com.taskora.identity.application.port.out.PasswordHasherPort;
import com.taskora.identity.domain.model.HashedPassword;
import com.taskora.identity.domain.model.PlainPassword;

/** Hachage compatible Symfony NativePasswordHasher (A2). */
public class SymfonyCompatiblePasswordHasher implements PasswordHasherPort {

  public SymfonyCompatiblePasswordHasher(int bcryptCost) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  @Override
  public HashedPassword hash(PlainPassword password) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  @Override
  public boolean matches(PlainPassword password, HashedPassword hash) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  @Override
  public void verifyAgainstDummy(PlainPassword password) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
