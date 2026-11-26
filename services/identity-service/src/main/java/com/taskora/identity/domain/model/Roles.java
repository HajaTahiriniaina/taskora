package com.taskora.identity.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Rôles stockés ; les rôles effectifs ajoutent toujours ROLE_USER, sans doublon. */
public record Roles(List<String> stored) {
  public static final String ROLE_USER = "ROLE_USER";

  public Roles {
    stored = List.copyOf(stored);
  }

  /** Ordre de User::getRoles() (PHP) : rôles stockés puis ROLE_USER, array_unique. */
  public List<String> effective() {
    LinkedHashSet<String> roles = new LinkedHashSet<>(stored);
    roles.add(ROLE_USER);
    return List.copyOf(new ArrayList<>(roles));
  }
}
