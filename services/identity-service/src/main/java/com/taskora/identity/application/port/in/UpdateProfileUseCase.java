package com.taskora.identity.application.port.in;

import com.taskora.identity.domain.model.ProfileUpdate;
import com.taskora.identity.domain.model.UserId;

/** Modification du profil. */
public interface UpdateProfileUseCase {
  /**
   * Applique une modification de profil tout ou rien (A8, SC-IDENT-019).
   *
   * @param id identifiant issu du jeton
   * @param update champs demandés (types déjà contrôlés par l'adapter web)
   * @return profil à jour
   * @throws com.taskora.identity.domain.exception.ValidationException nom vide ou couleur invalide
   * @throws com.taskora.identity.domain.exception.InvalidCurrentPasswordException mot de passe
   *     actuel faux ou absent
   */
  UserView update(UserId id, ProfileUpdate update);
}
