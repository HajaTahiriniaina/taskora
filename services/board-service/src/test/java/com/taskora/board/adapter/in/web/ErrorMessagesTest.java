package com.taskora.board.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A4 — chaque message du contrat T1, testé un par un (texte exact, apostrophes comprises). */
class ErrorMessagesTest {

  @Test
  @DisplayName("SC-BOARD-024 — messages 400")
  void badRequest() {
    assertThat(ErrorMessages.INVALID_JSON).isEqualTo("JSON invalide.");
    assertThat(ErrorMessages.INVALID_ROLE).isEqualTo("Rôle invalide.");
    assertThat(ErrorMessages.INVALID_EMAIL).isEqualTo("Adresse e-mail invalide.");
    assertThat(ErrorMessages.required("title")).isEqualTo("Le champ \"title\" est obligatoire.");
    assertThat(ErrorMessages.notString("email"))
        .isEqualTo("Le champ \"email\" doit être une chaîne de caractères.");
    assertThat(ErrorMessages.notBoolean("withDefaults"))
        .isEqualTo("Le champ \"withDefaults\" doit être un booléen.");
    assertThat(ErrorMessages.invalidField("userId")).isEqualTo("Le champ \"userId\" est invalide.");
  }

  @Test
  @DisplayName("SC-BOARD-021 — messages 404 (apostrophe typographique U+2019 de la corbeille)")
  void notFound() {
    assertThat(ErrorMessages.BOARD_NOT_FOUND).isEqualTo("Tableau introuvable.");
    assertThat(ErrorMessages.BOARD_IN_TRASH).isEqualTo("Ce tableau est dans la corbeille.");
    assertThat(ErrorMessages.BOARD_NOT_IN_TRASH)
        .isEqualTo("Ce tableau n’est pas dans la corbeille.");
    assertThat(ErrorMessages.BOARD_NOT_IN_TRASH).contains("’");
    assertThat(ErrorMessages.RESOURCE_NOT_FOUND).isEqualTo("Ressource introuvable.");
    assertThat(ErrorMessages.MEMBER_NOT_FOUND).isEqualTo("Membre introuvable.");
    assertThat(ErrorMessages.UNKNOWN_USER).isEqualTo("Utilisateur introuvable.");
  }

  @Test
  @DisplayName("SC-BOARD-007, 010, 014, 015 — messages 403")
  void forbidden() {
    assertThat(ErrorMessages.MANAGER_ONLY)
        .isEqualTo("Action réservée aux administrateurs du tableau.");
    assertThat(ErrorMessages.OWNER_ONLY_DELETE)
        .isEqualTo("Seul le propriétaire peut supprimer le tableau.");
    assertThat(ErrorMessages.OWNER_ONLY_RESTORE)
        .isEqualTo("Seul le propriétaire du tableau peut le restaurer ou le supprimer.");
    assertThat(ErrorMessages.OWNER_ROLE_LOCKED)
        .isEqualTo("Le rôle du propriétaire ne peut pas être modifié.");
    assertThat(ErrorMessages.OWNER_CANNOT_LEAVE)
        .isEqualTo("Le propriétaire ne peut pas quitter son tableau.");
  }

  @Test
  @DisplayName("SC-BOARD-009, 011, 030 — messages 409, 401 interne, 202 et 500")
  void others() {
    assertThat(ErrorMessages.ALREADY_MEMBER)
        .isEqualTo("Cet utilisateur est déjà membre du tableau.");
    assertThat(ErrorMessages.ALREADY_INVITED)
        .isEqualTo("Une invitation est déjà en attente pour cette adresse.");
    assertThat(ErrorMessages.EMAIL_MISMATCH)
        .isEqualTo("L'e-mail ne correspond pas à l'utilisateur.");
    assertThat(ErrorMessages.INTERNAL_DENIED).isEqualTo("Accès interne refusé.");
    assertThat(ErrorMessages.INTERNAL_ERROR).isEqualTo("Erreur interne du serveur.");
    assertThat(ErrorMessages.invited("nouveau@exemple.test"))
        .isEqualTo(
            "Invitation enregistrée : nouveau@exemple.test rejoindra le tableau à son inscription.");
  }
}
