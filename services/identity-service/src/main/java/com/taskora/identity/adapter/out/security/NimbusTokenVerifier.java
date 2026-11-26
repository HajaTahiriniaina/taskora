package com.taskora.identity.adapter.out.security;

import com.taskora.identity.application.port.out.TokenVerifierPort;
import com.taskora.identity.application.port.out.VerifiedToken;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;

/** Validation au format Lexik, précédence du §8.1 (A1). */
public class NimbusTokenVerifier implements TokenVerifierPort {

  public NimbusTokenVerifier(RSAPublicKey key, Clock clock) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  @Override
  public VerifiedToken verify(String token) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
