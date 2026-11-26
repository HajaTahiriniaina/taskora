package com.taskora.identity.adapter.out.security;

import com.taskora.identity.application.port.out.TokenIssuerPort;
import com.taskora.identity.domain.model.User;
import java.time.Clock;
import java.time.Duration;

/** Émission au format Lexik (A1). */
public class NimbusTokenIssuer implements TokenIssuerPort {

  public NimbusTokenIssuer(LexikKeyLoader keys, Clock clock, Duration ttl) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  @Override
  public String issue(User user) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
