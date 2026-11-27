package com.taskora.board.application.service;

import com.taskora.board.application.port.in.AddMemberResult;
import com.taskora.board.application.port.in.AddMemberUseCase;
import com.taskora.board.application.port.in.CancelInvitationUseCase;
import com.taskora.board.application.port.in.ChangeMemberRoleUseCase;
import com.taskora.board.application.port.in.MemberView;
import com.taskora.board.application.port.in.RemoveMemberUseCase;
import com.taskora.board.application.port.out.BoardAccessQueryPort;
import com.taskora.board.application.port.out.BoardReadModelPort;
import com.taskora.board.application.port.out.BoardRepositoryPort;
import com.taskora.board.application.port.out.InvitationRepositoryPort;
import com.taskora.board.application.port.out.MembershipRepositoryPort;
import com.taskora.board.application.port.out.OutboxPort;
import com.taskora.board.application.port.out.ParsedBody;
import com.taskora.board.application.port.out.RequestBodyParser;
import com.taskora.board.application.port.out.UnitOfWorkPort;
import com.taskora.board.application.port.out.UserDirectoryPort;
import com.taskora.board.domain.event.MemberAdded;
import com.taskora.board.domain.event.MemberRemoved;
import com.taskora.board.domain.event.MemberRoleChanged;
import com.taskora.board.domain.event.NotificationRequested;
import com.taskora.board.domain.event.NotificationTexts;
import com.taskora.board.domain.event.NotificationType;
import com.taskora.board.domain.exception.ConflictException;
import com.taskora.board.domain.exception.DomainMessages;
import com.taskora.board.domain.exception.ForbiddenException;
import com.taskora.board.domain.exception.ResourceNotFoundException;
import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.InvitationEmail;
import com.taskora.board.domain.model.Membership;
import com.taskora.board.domain.model.Role;
import com.taskora.board.domain.model.UserId;
import com.taskora.board.domain.model.UserSummary;
import com.taskora.board.domain.policy.BoardAccessPolicy;
import com.taskora.board.domain.policy.MembershipRules;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Membres et invitations (SC-BOARD-009 à 017, 031 à 035). Ordre : accès (B-ADR-3) → membre visé
 * (E28) → règles → corps ; verrou sur l'adhésion visée et index uniques traduits en 409 (B-ADR-4).
 */
public class MembershipService
    implements AddMemberUseCase,
        ChangeMemberRoleUseCase,
        RemoveMemberUseCase,
        CancelInvitationUseCase {
  private final UnitOfWorkPort unitOfWork;
  private final BoardAccessQueryPort access;
  private final BoardRepositoryPort boards;
  private final MembershipRepositoryPort memberships;
  private final InvitationRepositoryPort invitations;
  private final UserDirectoryPort users;
  private final BoardReadModelPort reads;
  private final OutboxPort outbox;
  private final RequestBodyParser parser;
  private final Clock clock;
  private final BoardAccessPolicy policy = new BoardAccessPolicy();

  /**
   * Assemble les cas d'utilisation.
   *
   * @param unitOfWork transactions
   * @param access données d'accès
   * @param boards tableaux (titre des notifications)
   * @param memberships adhésions
   * @param invitations invitations
   * @param users lecture de {@code taskora.user}
   * @param reads modèles de lecture
   * @param outbox outbox transactionnelle
   * @param parser analyse du corps
   * @param clock horloge
   */
  public MembershipService(
      UnitOfWorkPort unitOfWork,
      BoardAccessQueryPort access,
      BoardRepositoryPort boards,
      MembershipRepositoryPort memberships,
      InvitationRepositoryPort invitations,
      UserDirectoryPort users,
      BoardReadModelPort reads,
      OutboxPort outbox,
      RequestBodyParser parser,
      Clock clock) {
    this.unitOfWork = unitOfWork;
    this.access = access;
    this.boards = boards;
    this.memberships = memberships;
    this.invitations = invitations;
    this.users = users;
    this.reads = reads;
    this.outbox = outbox;
    this.parser = parser;
    this.clock = clock;
  }

  @Override
  public AddMemberResult add(UserId caller, BoardId board, byte[] rawBody) {
    return unitOfWork.inTransaction(() -> {
      policy.requireManager(access.load(board, caller));
      ParsedBody body = parser.parse(rawBody);
      String rawEmail = body.string("email").orElse("");
      Role role = MembershipRules.roleForAddition(body.raw("role") instanceof String s ? s : null);
      InvitationEmail email = InvitationEmail.of(rawEmail);
      Instant now = clock.instant();
      Optional<UserSummary> account = users.findByEmail(email.value());
      if (account.isEmpty()) {
        invitations.insert(board, email.value(), caller, now);
        return new AddMemberResult.Invited(email.value());
      }
      UserSummary added = account.get();
      List<UserId> members = memberships.memberIds(board);
      if (members.contains(added.id())) {
        // Lecture non verrouillante : un verrou d'intervalle ici provoquerait des interblocages
        // entre ajouts concurrents ; l'index unique tranche la course (409, B-ADR-4 point 3).
        throw new ConflictException(DomainMessages.ALREADY_MEMBER);
      }
      memberships.insert(new Membership(board, added.id(), role, false, now));
      outbox.append(
          new MemberAdded(UUID.randomUUID(), now, board, caller, added.id(), role, false));
      String title = boards.titleOf(board);
      String actorName = users.findById(caller).map(UserSummary::name).orElse(null);
      outbox.append(
          new NotificationRequested(
              UUID.randomUUID(),
              NotificationType.BOARD_INVITE,
              caller,
              List.of(added.id()),
              NotificationTexts.boardInvite(actorName, title),
              board));
      List<UserId> others =
          members.stream().filter(id -> !id.equals(caller) && !id.equals(added.id())).toList();
      if (!others.isEmpty()) {
        outbox.append(
            new NotificationRequested(
                UUID.randomUUID(),
                NotificationType.MEMBER_JOINED,
                caller,
                others,
                NotificationTexts.memberJoined(added.name(), title),
                board));
      }
      return new AddMemberResult.Added(reads.member(board, added.id()));
    });
  }

  @Override
  public MemberView changeRole(UserId caller, BoardId board, UserId target, byte[] rawBody) {
    return unitOfWork.inTransaction(() -> {
      policy.requireManager(access.load(board, caller));
      Membership membership = lockTarget(board, target);
      MembershipRules.ensureRoleChangeable(membership);
      ParsedBody body = parser.parse(rawBody);
      Role role = MembershipRules.roleForChange(body.raw("role") instanceof String s ? s : null);
      memberships.updateRoleUnlessOwner(board, target, role);
      outbox.append(
          new MemberRoleChanged(UUID.randomUUID(), clock.instant(), board, caller, target, role));
      return reads.member(board, target);
    });
  }

  @Override
  public void remove(UserId caller, BoardId board, UserId target) {
    unitOfWork.inTransaction(() -> {
      Role callerRole = policy.requireMember(access.load(board, caller));
      boolean self = caller.equals(target);
      if (!self && !callerRole.canManage()) {
        // Avant toute recherche du membre visé : aucune énumération par un simple membre (E28).
        throw new ForbiddenException(DomainMessages.MANAGER_ONLY);
      }
      Membership membership = lockTarget(board, target);
      MembershipRules.ensureRemovable(caller, callerRole, membership);
      memberships.removeWithAssignments(board, target);
      Instant now = clock.instant();
      outbox.append(new MemberRemoved(UUID.randomUUID(), now, board, caller, target, self));
      if (!self) {
        String actorName = users.findById(caller).map(UserSummary::name).orElse("");
        outbox.append(
            new NotificationRequested(
                UUID.randomUUID(),
                NotificationType.MEMBER_REMOVED,
                caller,
                List.of(target),
                NotificationTexts.memberRemoved(actorName, boards.titleOf(board)),
                null),
            board);
      }
      return board;
    });
  }

  @Override
  public void cancel(UserId caller, BoardId board, int invitationId) {
    unitOfWork.inTransaction(() -> {
      policy.requireManager(access.load(board, caller));
      if (!invitations.deleteFromBoard(board, invitationId)) {
        throw new ResourceNotFoundException();
      }
      return board;
    });
  }

  private Membership lockTarget(BoardId board, UserId target) {
    return memberships
        .lock(board, target)
        .orElseThrow(() -> new ResourceNotFoundException(DomainMessages.MEMBER_NOT_FOUND));
  }
}
