package com.taskora.identity.application.port.in;

import com.taskora.identity.domain.model.UserId;

/** Lecture du profil. */
public interface GetProfileUseCase {
  /**
   * Lit le profil public du compte authentifié (SC-IDENT-014).
   *
   * @param id identifiant issu du jeton
   * @return profil {id, name, email, avatarColor}
   * @throws com.taskora.identity.domain.exception.TokenRejectedException si le compte a disparu
   */
  UserView get(UserId id);
}
