package com.taskora.board.application.service;

import com.taskora.board.application.port.in.BoardSummaryView;
import com.taskora.board.application.port.in.BoardView;
import com.taskora.board.application.port.in.CreateBoardUseCase;
import com.taskora.board.application.port.in.RestoreBoardUseCase;
import com.taskora.board.application.port.in.ToggleFavoriteUseCase;
import com.taskora.board.application.port.in.TrashBoardUseCase;
import com.taskora.board.application.port.in.UpdateBoardUseCase;
import com.taskora.board.application.port.out.BoardAccessQueryPort;
import com.taskora.board.application.port.out.BoardReadModelPort;
import com.taskora.board.application.port.out.BoardRepositoryPort;
import com.taskora.board.application.port.out.BoardRepositoryPort.BoardChanges;
import com.taskora.board.application.port.out.MembershipRepositoryPort;
import com.taskora.board.application.port.out.OutboxPort;
import com.taskora.board.application.port.out.ParsedBody;
import com.taskora.board.application.port.out.RequestBodyParser;
import com.taskora.board.application.port.out.UnitOfWorkPort;
import com.taskora.board.domain.event.BoardCreated;
import com.taskora.board.domain.event.BoardRestored;
import com.taskora.board.domain.event.BoardTrashed;
import com.taskora.board.domain.exception.DomainMessages;
import com.taskora.board.domain.model.Background;
import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.BoardTitle;
import com.taskora.board.domain.model.DefaultBoardContent;
import com.taskora.board.domain.model.Membership;
import com.taskora.board.domain.model.NewBoard;
import com.taskora.board.domain.model.Role;
import com.taskora.board.domain.model.UserId;
import com.taskora.board.domain.policy.BoardAccessPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Tableaux : création, modification, corbeille, favori, restauration. Chaque cas d'utilisation
 * contrôle l'accès (B-ADR-3) avant d'analyser le corps, dans une seule transaction.
 */
public class BoardCommandService
    implements CreateBoardUseCase,
        UpdateBoardUseCase,
        TrashBoardUseCase,
        ToggleFavoriteUseCase,
        RestoreBoardUseCase {
  private static final String TITLE = "title";
  private static final String DESCRIPTION = "description";

  private final UnitOfWorkPort unitOfWork;
  private final BoardAccessQueryPort access;
  private final BoardRepositoryPort boards;
  private final MembershipRepositoryPort memberships;
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
   * @param boards écritures sur les tableaux
   * @param memberships écritures sur les adhésions
   * @param reads modèles de lecture
   * @param outbox outbox transactionnelle
   * @param parser analyse du corps
   * @param clock horloge
   */
  public BoardCommandService(
      UnitOfWorkPort unitOfWork,
      BoardAccessQueryPort access,
      BoardRepositoryPort boards,
      MembershipRepositoryPort memberships,
      BoardReadModelPort reads,
      OutboxPort outbox,
      RequestBodyParser parser,
      Clock clock) {
    this.unitOfWork = unitOfWork;
    this.access = access;
    this.boards = boards;
    this.memberships = memberships;
    this.reads = reads;
    this.outbox = outbox;
    this.parser = parser;
    this.clock = clock;
  }

  @Override
  public BoardView create(UserId caller, byte[] rawBody) {
    ParsedBody body = parser.parse(rawBody);
    String rawTitle = body.string(TITLE).orElse("");
    String description = nullableString(body, DESCRIPTION);
    boolean withDefaults = body.bool("withDefaults").orElse(true);
    Background background = background(body).orElse(Background.AURORA);
    BoardTitle title = BoardTitle.of(rawTitle);
    return unitOfWork.inTransaction(() -> {
      Instant now = clock.instant();
      BoardId id = boards.insert(new NewBoard(title, description, background, caller, now));
      memberships.insert(new Membership(id, caller, Role.OWNER, false, now));
      if (withDefaults) {
        boards.insertDefaultContent(id, DefaultBoardContent.lists(), DefaultBoardContent.labels());
      }
      outbox.append(new BoardCreated(UUID.randomUUID(), now, id, caller, caller));
      return reads.board(id, caller);
    });
  }

  @Override
  public BoardSummaryView update(UserId caller, BoardId board, byte[] rawBody) {
    return unitOfWork.inTransaction(() -> {
      policy.requireMember(access.load(board, caller));
      ParsedBody body = parser.parse(rawBody);
      String rawTitle = body.string(TITLE).orElse(null);
      boolean descriptionChanged = body.has(DESCRIPTION);
      String description = falsyToNull(nullableString(body, DESCRIPTION));
      Boolean showCardNumbers = body.bool("showCardNumbers").orElse(null);
      Background background = background(body).orElse(null);
      BoardTitle title = rawTitle == null ? null : BoardTitle.of(rawTitle);
      BoardChanges changes =
          new BoardChanges(title, descriptionChanged, description, background, showCardNumbers);
      if (title != null || descriptionChanged || background != null || showCardNumbers != null) {
        boards.update(board, changes);
      }
      return reads.summaryWithoutCounters(board, caller);
    });
  }

  @Override
  public void trash(UserId caller, BoardId board) {
    unitOfWork.inTransaction(() -> {
      policy.requireOwner(access.load(board, caller), DomainMessages.OWNER_ONLY_DELETE);
      Instant now = clock.instant();
      boards.markTrashed(board, caller, now);
      outbox.append(new BoardTrashed(UUID.randomUUID(), now, board, caller));
      return board;
    });
  }

  @Override
  public boolean toggle(UserId caller, BoardId board) {
    return unitOfWork.inTransaction(() -> {
      policy.requireMember(access.load(board, caller));
      return memberships.toggleFavorite(board, caller);
    });
  }

  @Override
  public BoardId restore(UserId caller, BoardId board) {
    return unitOfWork.inTransaction(() -> {
      policy.requireOwnerOfTrashedBoard(access.load(board, caller));
      boards.restore(board);
      outbox.append(new BoardRestored(UUID.randomUUID(), clock.instant(), board, caller));
      return board;
    });
  }

  /** Chaîne ou {@code null} explicite ; absent → {@code null} ; autre type → 400 (E22). */
  private static @Nullable String nullableString(ParsedBody body, String name) {
    return body.raw(name) == null ? null : body.string(name).orElse(null);
  }

  /** Règle PHP « falsy » de Symfony en modification : "" et "0" deviennent null (FA11). */
  private static @Nullable String falsyToNull(@Nullable String description) {
    return description == null || description.isEmpty() || "0".equals(description)
        ? null
        : description;
  }

  /** Fond : une des dix valeurs, sinon ignoré (non chaîne compris). */
  private static Optional<Background> background(ParsedBody body) {
    return Background.parse(body.raw("background") instanceof String s ? s : null);
  }
}
