package com.taskora.identity.adapter.in.web;

/** Messages exacts renvoyés au client (A4), identiques à Symfony sauf écarts corrigés E15–E21. */
public final class ErrorMessages {
  public static final String INVALID_JSON = "JSON invalide.";
  public static final String EMAIL_ALREADY_USED = "Un compte existe déjà avec cette adresse e-mail.";
  public static final String WRONG_CURRENT_PASSWORD = "Mot de passe actuel incorrect.";
  public static final String PASSWORD_TOO_LONG = "Le mot de passe ne doit pas dépasser 4096 octets.";
  public static final String INTERNAL_ERROR = "Erreur interne du serveur.";
  public static final String NOT_FOUND = "Ressource introuvable.";
  public static final String METHOD_NOT_ALLOWED = "Méthode non autorisée.";

  /** Messages 401 au format Lexik ({"code":401,"message":…}). */
  public static final String INVALID_CREDENTIALS = "Invalid credentials.";
  public static final String JWT_NOT_FOUND = "JWT Token not found";
  public static final String JWT_INVALID = "Invalid JWT Token";
  public static final String JWT_EXPIRED = "Expired JWT Token";
  public static final String JWT_MISSING_USERNAME =
      "Unable to find key \"username\" in the token payload.";

  private ErrorMessages() {}

  public static String required(String field) {
    return "Le champ \"" + field + "\" est obligatoire.";
  }

  public static String notAString(String field) {
    return "Le champ \"" + field + "\" doit être une chaîne de caractères.";
  }
}
