package com.taskora.identity.application.service;

import com.taskora.identity.application.port.in.AuthResult;
import com.taskora.identity.application.port.in.DeliverPendingMessagesUseCase;
import com.taskora.identity.application.port.in.RegisterUserCommand;
import com.taskora.identity.application.port.in.RegisterUserUseCase;
import com.taskora.identity.application.port.out.InvitationClaim;
import com.taskora.identity.application.port.out.LegacyUserProjectionPort;
import com.taskora.identity.application.port.out.OutboxPort;
import com.taskora.identity.application.port.out.PasswordHasherPort;
import com.taskora.identity.application.port.out.TokenIssuerPort;
import com.taskora.identity.application.port.out.UnitOfWorkPort;
import com.taskora.identity.application.port.out.UserRepositoryPort;
import com.taskora.identity.domain.event.UserRegistered;
import com.taskora.identity.domain.exception.EmailAlreadyUsedException;
import com.taskora.identity.domain.model.AvatarColor;
import com.taskora.identity.domain.model.Email;
import com.taskora.identity.domain.model.HashedPassword;
import com.taskora.identity.domain.model.NewUser;
import com.taskora.identity.domain.model.PersonName;
import com.taskora.identity.domain.model.PlainPassword;
import com.taskora.identity.domain.model.Roles;
import com.taskora.identity.domain.model.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Inscription (A7, A9, A10, A11). */
public class RegisterUserService implements RegisterUserUseCase {
  /** Délai du claim de rattrapage (invitation créée par Symfony pendant l'inscription). */
  static final Duration CATCH_UP_DELAY = Duration.ofSeconds(60);

  private static final System.Logger LOG = System.getLogger(RegisterUserService.class.getName());

  private final UserRepositoryPort users;
  private final PasswordHasherPort hasher;
  private final TokenIssuerPort tokens;
  private final LegacyUserProjectionPort projection;
  private final OutboxPort outbox;
  private final UnitOfWorkPort unitOfWork;
  private final DeliverPendingMessagesUseCase delivery;
  private final Clock clock;
  private final ZoneId legacyZone;
  private final RandomGenerator random;

  /**
   * Assemble le cas d'utilisation.
   *
   * @param users stockage des comptes
   * @param hasher hachage compatible Symfony
   * @param tokens émetteur de jetons
   * @param projection projection taskora.user
   * @param outbox outbox transactionnelle
   * @param unitOfWork transaction de l'inscription
   * @param delivery livraison synchrone du premier claim, après validation
   * @param clock horloge (created_at, événements, échéances)
   * @param legacyZone fuseau de created_at (Europe/Paris, comme PHP)
   * @param random tirage de la couleur d'avatar
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
    this.users = users;
    this.hasher = hasher;
    this.tokens = tokens;
    this.projection = projection;
    this.outbox = outbox;
    this.unitOfWork = unitOfWork;
    this.delivery = delivery;
    this.clock = clock;
    this.legacyZone = legacyZone;
    this.random = random;
  }

  @Override
  public AuthResult register(RegisterUserCommand command) {
    // Ordre des contrôles : name → email → longueur minimale puis maximale du mot de passe →
    // unicité.
    final PersonName name = PersonName.of(command.name());
    Email email = Email.fromRegistration(command.email());
    PlainPassword password = PlainPassword.of(command.password());
    password.requireMinimumLength();
    password.requireMaximumLength();
    if (users.existsByEmail(email)) {
      throw new EmailAlreadyUsedException();
    }

    HashedPassword hash = hasher.hash(password);
    Instant now = clock.instant();
    NewUser candidate =
        new NewUser(
            email,
            name,
            hash,
            AvatarColor.random(random),
            new Roles(List.of()),
            LocalDateTime.ofInstant(now, legacyZone));
    UUID immediateClaimId = UUID.randomUUID();
    UUID catchUpClaimId = UUID.randomUUID();
    UUID registeredEventId = UUID.randomUUID();

    User user =
        unitOfWork.inTransaction(() -> {
          User created = users.insert(candidate);
          projection.upsert(created);
          outbox.appendInvitationClaim(
              new InvitationClaim(immediateClaimId, created.id(), created.email()), now);
          outbox.appendInvitationClaim(
              new InvitationClaim(catchUpClaimId, created.id(), created.email()),
              now.plus(CATCH_UP_DELAY));
          outbox.appendEvent(
              new UserRegistered(
                  registeredEventId,
                  now,
                  created.id(),
                  created.email().value(),
                  created.name().value(),
                  created.avatarColor().value()));
          return created;
        });

    deliverImmediately(immediateClaimId, user);
    return new AuthResult(tokens.issue(user), UserViews.of(user));
  }

  /**
   * Livraison synchrone après validation ; un échec laisse le message au relais (201 quand même).
   */
  private void deliverImmediately(UUID claimId, User user) {
    try {
      if (!delivery.deliverNow(claimId)) {
        LOG.log(
            System.Logger.Level.INFO,
            "Invitations de l''utilisateur {0} : livraison différée au relais",
            user.id().value());
      }
    } catch (RuntimeException e) {
      LOG.log(
          System.Logger.Level.WARNING,
          "Invitations de l''utilisateur {0} : livraison synchrone en échec ({1}), relais",
          user.id().value(),
          e.getClass().getSimpleName());
    }
  }
}
