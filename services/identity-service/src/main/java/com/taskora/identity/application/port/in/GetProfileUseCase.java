package com.taskora.identity.application.port.in;

import com.taskora.identity.domain.model.UserId;

/** Lecture du profil. */
public interface GetProfileUseCase {
  UserView get(UserId id);
}
