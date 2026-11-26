package com.taskora.identity.domain.model;

/** Nom affiché : trim → obligatoire → tronqué à 120 points de code. */
public record PersonName(String value) {
  public static PersonName of(String raw) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
