package com.taskora.board.adapter.in.web;

/**
 * Messages exacts du contrat T1 (board-service.yaml, board-service.internal.yaml), testés un par un
 * (A4). Corps {@code {"error": <message>}}, sauf les 401 Lexik ({@code {"code":401,"message"}}).
 */
public final class ErrorMessages {
  /** 400 : corps non JSON ou JSON non objet (U13). */
  public static final String INVALID_JSON = "JSON invalide.";

  /** 400 : rôle hors {@code admin}/{@code member}. */
  public static final String INVALID_ROLE = "Rôle invalide.";

  /** 400 : e-mail refusé par {@code FILTER_VALIDATE_EMAIL}. */
  public static final String INVALID_EMAIL = "Adresse e-mail invalide.";

  /** 404 : tableau inexistant ou non-membre. */
  public static final String BOARD_NOT_FOUND = "Tableau introuvable.";

  /** 404 : membre d'un tableau dans la corbeille. */
  public static final String BOARD_IN_TRASH = "Ce tableau est dans la corbeille.";

  /** 404 : routes {@code /api/trash/boards/{id}/**}. */
  public static final String BOARD_NOT_IN_TRASH = "Ce tableau n’est pas dans la corbeille.";

  /** 404 : identifiant non entier positif (E12), invitation introuvable (E28). */
  public static final String RESOURCE_NOT_FOUND = "Ressource introuvable.";

  /** 404 : membre visé inexistant ou non membre (E28). */
  public static final String MEMBER_NOT_FOUND = "Membre introuvable.";

  /** 403 : action de gestion par un simple membre. */
  public static final String MANAGER_ONLY = "Action réservée aux administrateurs du tableau.";

  /** 403 : mise à la corbeille par un autre que le propriétaire. */
  public static final String OWNER_ONLY_DELETE = "Seul le propriétaire peut supprimer le tableau.";

  /** 403 : restauration par un autre que le propriétaire. */
  public static final String OWNER_ONLY_RESTORE =
      "Seul le propriétaire du tableau peut le restaurer ou le supprimer.";

  /** 403 : rôle du propriétaire visé. */
  public static final String OWNER_ROLE_LOCKED =
      "Le rôle du propriétaire ne peut pas être modifié.";

  /** 403 : retrait ou départ du propriétaire. */
  public static final String OWNER_CANNOT_LEAVE =
      "Le propriétaire ne peut pas quitter son tableau.";

  /** 409 : compte déjà membre. */
  public static final String ALREADY_MEMBER = "Cet utilisateur est déjà membre du tableau.";

  /** 409 : invitation déjà en attente. */
  public static final String ALREADY_INVITED =
      "Une invitation est déjà en attente pour cette adresse.";

  /** 401 interne : secret absent ou d'un autre appelant (U12). */
  public static final String INTERNAL_DENIED = "Accès interne refusé.";

  /** 404 interne : utilisateur pas encore visible. */
  public static final String UNKNOWN_USER = "Utilisateur introuvable.";

  /** 409 interne : e-mail différent de celui du compte. */
  public static final String EMAIL_MISMATCH = "L'e-mail ne correspond pas à l'utilisateur.";

  /** 5xx. */
  public static final String INTERNAL_ERROR = "Erreur interne du serveur.";

  private ErrorMessages() {}

  /**
   * 400 : champ obligatoire vide après trim.
   *
   * @param field nom du champ
   * @return message
   */
  public static String required(String field) {
    return "Le champ \"" + field + "\" est obligatoire.";
  }

  /**
   * 400 : champ texte non chaîne (E22).
   *
   * @param field nom du champ
   * @return message
   */
  public static String notString(String field) {
    return "Le champ \"" + field + "\" doit être une chaîne de caractères.";
  }

  /**
   * 400 : champ booléen non booléen (E23).
   *
   * @param field nom du champ
   * @return message
   */
  public static String notBoolean(String field) {
    return "Le champ \"" + field + "\" doit être un booléen.";
  }

  /**
   * 400 interne : champ du claim invalide.
   *
   * @param field nom du champ
   * @return message
   */
  public static String invalidField(String field) {
    return "Le champ \"" + field + "\" est invalide.";
  }

  /**
   * 202 : invitation enregistrée.
   *
   * @param email e-mail normalisé
   * @return message
   */
  public static String invited(String email) {
    return "Invitation enregistrée : " + email + " rejoindra le tableau à son inscription.";
  }
}
