package com.taskora.identity.domain.model;

/** Hash de mot de passe ; jamais affiché. */
public record HashedPassword(String value) {
  @Override
  public String toString() {
    return "HashedPassword[***]";
  }
}
