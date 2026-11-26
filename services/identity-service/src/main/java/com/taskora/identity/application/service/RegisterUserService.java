package com.taskora.identity.application.service;

import com.taskora.identity.application.port.in.AuthResult;
import com.taskora.identity.application.port.in.DeliverPendingMessagesUseCase;
import com.taskora.identity.application.port.in.RegisterUserCommand;
import com.taskora.identity.application.port.in.RegisterUserUseCase;
import com.taskora.identity.application.port.out.LegacyUserProjectionPort;
import com.taskora.identity.application.port.out.OutboxPort;
import com.taskora.identity.application.port.out.PasswordHasherPort;
import com.taskora.identity.application.port.out.TokenIssuerPort;
import com.taskora.identity.application.port.out.UnitOfWorkPort;
import com.taskora.identity.application.port.out.UserRepositoryPort;
import java.time.Clock;
import java.time.ZoneId;
import java.util.random.RandomGenerator;

/** Inscription (A7, A9, A10, A11). */
public class RegisterUserService implements RegisterUserUseCase {

  /**
   * @param legacyZone fuseau de created_at (Europe/Paris, comme PHP)
   */
  public RegisterUserService(
      UserRepositoryPort users,
      PasswordHasherPort hasher,
      TokenIssuerPort tokens,
      LegacyUserProjectionPort projection,
      OutboxPort outbox,
      UnitOfWorkPort unitOfWork,
      DeliverPendingMessagesUseCase delivery,
      Clock clock,
      ZoneId legacyZone,
      RandomGenerator random) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  @Override
  public AuthResult register(RegisterUserCommand command) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
