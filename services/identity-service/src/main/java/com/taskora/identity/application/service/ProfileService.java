package com.taskora.identity.application.service;

import com.taskora.identity.application.port.in.GetProfileUseCase;
import com.taskora.identity.application.port.in.UpdateProfileUseCase;
import com.taskora.identity.application.port.in.UserView;
import com.taskora.identity.application.port.out.LegacyUserProjectionPort;
import com.taskora.identity.application.port.out.OutboxPort;
import com.taskora.identity.application.port.out.PasswordHasherPort;
import com.taskora.identity.application.port.out.UnitOfWorkPort;
import com.taskora.identity.application.port.out.UserRepositoryPort;
import com.taskora.identity.domain.model.ProfileUpdate;
import com.taskora.identity.domain.model.UserId;
import java.time.Clock;

/** Lecture et modification du profil (A8). */
public class ProfileService implements GetProfileUseCase, UpdateProfileUseCase {

  public ProfileService(
      UserRepositoryPort users,
      PasswordHasherPort hasher,
      LegacyUserProjectionPort projection,
      OutboxPort outbox,
      UnitOfWorkPort unitOfWork,
      Clock clock) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  @Override
  public UserView get(UserId id) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  @Override
  public UserView update(UserId id, ProfileUpdate update) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
