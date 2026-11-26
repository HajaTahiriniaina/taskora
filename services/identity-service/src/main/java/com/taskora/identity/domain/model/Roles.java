package com.taskora.identity.domain.model;

import java.util.List;

/** Rôles stockés ; les rôles effectifs ajoutent toujours ROLE_USER, sans doublon. */
public record Roles(List<String> stored) {
  public List<String> effective() {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
