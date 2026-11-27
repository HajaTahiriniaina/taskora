package com.taskora.board.domain.policy;

import com.taskora.board.domain.exception.BoardInTrashException;
import com.taskora.board.domain.exception.BoardNotFoundException;
import com.taskora.board.domain.exception.DomainMessages;
import com.taskora.board.domain.exception.ForbiddenException;
import com.taskora.board.domain.exception.NotInTrashException;
import com.taskora.board.domain.model.Role;

/**
 * Politique d'accès (B-ADR-3, spec §5.3), Java pur. Un non-membre est traité exactement comme un
 * tableau inexistant ; le contrôle précède toute analyse du corps.
 */
public final class BoardAccessPolicy {

  /** Crée la politique (sans état). */
  public BoardAccessPolicy() {
    // sans état
  }

  /**
   * Accès d'un membre à un tableau ouvert.
   *
   * @param view données d'accès
   * @return rôle de l'appelant
   * @throws com.taskora.board.domain.exception.BoardNotFoundException inexistant ou non-membre
   * @throws com.taskora.board.domain.exception.BoardInTrashException membre, tableau dans la
   *     corbeille
   */
  public Role requireMember(BoardAccessView view) {
    Role role = view.callerRole();
    if (!view.exists() || role == null) {
      throw new BoardNotFoundException();
    }
    if (view.deletedAt() != null) {
      throw new BoardInTrashException();
    }
    return role;
  }

  /**
   * Accès d'un gestionnaire ({@code owner} ou {@code admin}).
   *
   * @param view données d'accès
   * @return rôle de l'appelant
   * @throws com.taskora.board.domain.exception.ForbiddenException {@code Action réservée aux
   *     administrateurs du tableau.}
   */
  public Role requireManager(BoardAccessView view) {
    Role role = requireMember(view);
    if (!role.canManage()) {
      throw new ForbiddenException(DomainMessages.MANAGER_ONLY);
    }
    return role;
  }

  /**
   * Action réservée au propriétaire d'un tableau ouvert (mise à la corbeille).
   *
   * @param view données d'accès
   * @param forbiddenMessage message du 403 pour un autre membre
   * @throws com.taskora.board.domain.exception.ForbiddenException membre non propriétaire
   */
  public void requireOwner(BoardAccessView view, String forbiddenMessage) {
    if (requireMember(view) != Role.OWNER) {
      throw new ForbiddenException(forbiddenMessage);
    }
  }

  /**
   * Restauration ({@code /api/trash/boards/{id}/restore}) : propriétaire d'un tableau dans la
   * corbeille.
   *
   * @param view données d'accès
   * @throws com.taskora.board.domain.exception.NotInTrashException absent de la corbeille,
   *     inexistant ou non-membre
   * @throws com.taskora.board.domain.exception.ForbiddenException {@code Seul le propriétaire du
   *     tableau peut le restaurer ou le supprimer.}
   */
  public void requireOwnerOfTrashedBoard(BoardAccessView view) {
    if (!view.exists() || view.callerRole() == null || view.deletedAt() == null) {
      throw new NotInTrashException();
    }
    if (view.callerRole() != Role.OWNER) {
      throw new ForbiddenException(DomainMessages.OWNER_ONLY_RESTORE);
    }
  }
}
