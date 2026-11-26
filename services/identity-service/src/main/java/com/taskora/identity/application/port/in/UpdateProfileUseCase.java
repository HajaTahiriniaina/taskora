package com.taskora.identity.application.port.in;

import com.taskora.identity.domain.model.ProfileUpdate;
import com.taskora.identity.domain.model.UserId;

/** Modification du profil. */
public interface UpdateProfileUseCase {
  UserView update(UserId id, ProfileUpdate update);
}
