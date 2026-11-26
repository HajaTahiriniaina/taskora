package com.taskora.identity.application.port.out;

import com.taskora.identity.domain.model.User;

/** Émission de JWT. */
public interface TokenIssuerPort {
  /**
   * Émet un JWT au format Lexik (RS256, iat/exp entiers, roles, username, uid).
   *
   * @param user compte authentifié
   * @return jeton compact signé
   */
  String issue(User user);
}
