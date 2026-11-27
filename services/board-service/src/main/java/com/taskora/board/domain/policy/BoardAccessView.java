package com.taskora.board.domain.policy;

import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.Role;
import java.time.Instant;

/**
 * Données d'accès chargées par une seule requête (B-ADR-3 point 1) : {@code SELECT b.id,
 * b.deleted_at, m.role FROM board b LEFT JOIN board_member m … WHERE b.id = ?}.
 *
 * @param boardId tableau demandé
 * @param exists le tableau existe
 * @param deletedAt mise à la corbeille, ou {@code null}
 * @param callerRole rôle de l'appelant, ou {@code null} s'il n'est pas membre
 */
public record BoardAccessView(BoardId boardId, boolean exists, Instant deletedAt, Role callerRole) {

  /**
   * Tableau inexistant.
   *
   * @param boardId identifiant demandé
   * @return vue d'un tableau absent
   */
  public static BoardAccessView missing(BoardId boardId) {
    return new BoardAccessView(boardId, false, null, null);
  }
}
