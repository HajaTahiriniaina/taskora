package com.taskora.board.application.port.in;

/** Résultat d'un ajout : compte existant ajouté (201) ou invitation enregistrée (202). */
public sealed interface AddMemberResult {

  /**
   * Compte existant ajouté.
   *
   * @param member nouveau membre
   */
  record Added(MemberView member) implements AddMemberResult {}

  /**
   * Invitation enregistrée.
   *
   * @param email e-mail normalisé
   */
  record Invited(String email) implements AddMemberResult {}
}
