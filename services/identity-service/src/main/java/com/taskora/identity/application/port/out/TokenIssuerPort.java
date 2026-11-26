package com.taskora.identity.application.port.out;

import com.taskora.identity.domain.model.User;

/** Émission de JWT. */
public interface TokenIssuerPort {
  String issue(User user);
}
