package com.taskora.board.application.service;

import com.taskora.board.application.port.in.ClaimInvitationsUseCase;
import com.taskora.board.application.port.in.ClaimResult;
import com.taskora.board.application.port.out.BoardRepositoryPort;
import com.taskora.board.application.port.out.InvitationRepositoryPort;
import com.taskora.board.application.port.out.MembershipRepositoryPort;
import com.taskora.board.application.port.out.OutboxPort;
import com.taskora.board.application.port.out.ParsedBody;
import com.taskora.board.application.port.out.RequestBodyParser;
import com.taskora.board.application.port.out.UnitOfWorkPort;
import com.taskora.board.application.port.out.UserDirectoryPort;
import com.taskora.board.domain.event.MemberAdded;
import com.taskora.board.domain.event.NotificationRequested;
import com.taskora.board.domain.event.NotificationTexts;
import com.taskora.board.domain.event.NotificationType;
import com.taskora.board.domain.exception.ConflictException;
import com.taskora.board.domain.exception.DomainMessages;
import com.taskora.board.domain.exception.ResourceNotFoundException;
import com.taskora.board.domain.exception.ValidationException;
import com.taskora.board.domain.model.Invitation;
import com.taskora.board.domain.model.Membership;
import com.taskora.board.domain.model.PhpString;
import com.taskora.board.domain.model.Role;
import com.taskora.board.domain.model.UserId;
import com.taskora.board.domain.model.UserSummary;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Consommation des invitations d'un nouvel utilisateur (SC-BOARD-030, contrat
 * identity-service.internal.yaml) : idempotente, une transaction, {@code board_invite} par l'outbox
 * avec l'inviteur comme acteur (E24 : toujours {@code member}).
 */
public class InvitationClaimService implements ClaimInvitationsUseCase {
  private static final Pattern UUID_FORMAT =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private final UnitOfWorkPort unitOfWork;
  private final InvitationRepositoryPort invitations;
  private final MembershipRepositoryPort memberships;
  private final BoardRepositoryPort boards;
  private final UserDirectoryPort users;
  private final OutboxPort outbox;
  private final RequestBodyParser parser;
  private final Clock clock;

  /**
   * Assemble le cas d'utilisation.
   *
   * @param unitOfWork transactions
   * @param invitations invitations
   * @param memberships adhésions
   * @param boards tableaux (titre des notifications)
   * @param users lecture de {@code taskora.user}
   * @param outbox outbox transactionnelle
   * @param parser analyse du corps
   * @param clock horloge
   */
  public InvitationClaimService(
      UnitOfWorkPort unitOfWork,
      InvitationRepositoryPort invitations,
      MembershipRepositoryPort memberships,
      BoardRepositoryPort boards,
      UserDirectoryPort users,
      OutboxPort outbox,
      RequestBodyParser parser,
      Clock clock) {
    this.unitOfWork = unitOfWork;
    this.invitations = invitations;
    this.memberships = memberships;
    this.boards = boards;
    this.users = users;
    this.outbox = outbox;
    this.parser = parser;
    this.clock = clock;
  }

  @Override
  public ClaimResult claim(byte[] rawBody) {
    ParsedBody body = parser.parse(rawBody);
    for (String field : List.of("eventId", "userId", "email")) {
      Object value = body.raw(field);
      if (value == null || "".equals(value)) {
        throw new ValidationException(DomainMessages.required(field));
      }
    }
    if (!(body.raw("eventId") instanceof String eventId)
        || !UUID_FORMAT.matcher(eventId).matches()) {
      throw new ValidationException(DomainMessages.invalidField("eventId"));
    }
    if (!(body.raw("userId") instanceof Number number)
        || !(number instanceof Integer || number instanceof Long)
        || number.longValue() < 1
        || number.longValue() > Integer.MAX_VALUE) {
      throw new ValidationException(DomainMessages.invalidField("userId"));
    }
    if (!(body.raw("email") instanceof String email)) {
      throw new ValidationException(DomainMessages.invalidField("email"));
    }
    UserId userId = new UserId(number.intValue());
    return unitOfWork.inTransaction(() -> {
      UserSummary user =
          users
              .findById(userId)
              .orElseThrow(() -> new ResourceNotFoundException(DomainMessages.UNKNOWN_USER));
      String normalized = PhpString.lower(PhpString.trim(email));
      if (!normalized.equals(PhpString.lower(user.email()))) {
        throw new ConflictException(DomainMessages.EMAIL_MISMATCH);
      }
      return consume(user, normalized);
    });
  }

  /** Adhésion ; une course avec un ajout direct garde l'adhésion existante (jamais 500). */
  private boolean join(Membership membership) {
    try {
      memberships.insert(membership);
      return true;
    } catch (ConflictException alreadyMember) {
      return false;
    }
  }

  private ClaimResult consume(UserSummary user, String email) {
    Instant now = clock.instant();
    List<Integer> boardIds = new ArrayList<>();
    for (Invitation invitation : invitations.lockByEmail(email)) {
      if (!memberships.memberIds(invitation.boardId()).contains(user.id())
          && join(new Membership(invitation.boardId(), user.id(), Role.MEMBER, false, now))) {
        UserId inviter = invitation.invitedBy();
        String inviterName =
            inviter == null ? null : users.findById(inviter).map(UserSummary::name).orElse(null);
        outbox.append(
            new MemberAdded(
                UUID.randomUUID(),
                now,
                invitation.boardId(),
                inviter,
                user.id(),
                Role.MEMBER,
                true));
        outbox.append(
            new NotificationRequested(
                UUID.randomUUID(),
                NotificationType.BOARD_INVITE,
                inviter,
                List.of(user.id()),
                NotificationTexts.boardInvite(inviterName, boards.titleOf(invitation.boardId())),
                invitation.boardId()));
        boardIds.add(invitation.boardId().value());
      }
      invitations.delete(invitation.id());
    }
    return new ClaimResult(boardIds.size(), List.copyOf(boardIds));
  }
}
