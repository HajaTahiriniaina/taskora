package com.taskora.identity.adapter.in.web;

/**
 * Lecture stricte du corps JSON (nom conservé ; aucune conversion PHP depuis E16, E17, E20, E21).
 */
public final class PhpJsonBody {
  private PhpJsonBody() {}

  /**
   * Corps vide, non JSON ou JSON qui n'est pas un objet → ValidationException("JSON invalide.")
   * (E16, E20).
   */
  public static PhpJsonBody parse(byte[] body) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  public boolean has(String key) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /** Présent et non null. */
  public boolean isSetLikePhp(String key) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /**
   * Inscription et connexion : null si la clé est absente ; présente et non chaîne (null compris) →
   * ValidationException("Le champ \"key\" doit être une chaîne de caractères.") (E17, E19).
   */
  public String requiredString(String key) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /** PATCH /api/me : null si absente ou null ; autre non-chaîne → même exception (E17). */
  public String optionalString(String key) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
