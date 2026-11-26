package com.taskora.identity.adapter.out.legacy;

import java.util.regex.Pattern;

/**
 * Nom du schéma historique (identity.legacy.schema), validé [a-z_]+ : il est inséré dans le SQL, il
 * ne vient jamais d'une entrée utilisateur.
 */
public record LegacySchema(String name) {
  private static final Pattern VALID = Pattern.compile("[a-z_]+");

  public LegacySchema {
    if (name == null || !VALID.matcher(name).matches()) {
      throw new IllegalArgumentException("identity.legacy.schema invalide : [a-z_]+ attendu");
    }
  }

  /** Table historique qualifiée, ex. taskora.`user`. */
  String userTable() {
    return name + ".`user`";
  }
}
