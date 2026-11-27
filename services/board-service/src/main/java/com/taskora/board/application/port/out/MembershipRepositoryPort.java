package com.taskora.board.application.port.out;

import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.Membership;
import com.taskora.board.domain.model.Role;
import com.taskora.board.domain.model.UserId;
import java.util.List;
import java.util.Optional;

/** Écritures sur {@code board_member} et {@code card_assignee} (B-ADR-4 point 3). */
public interface MembershipRepositoryPort {

  /**
   * Insère une adhésion ; violation de l'index unique → conflit, jamais 500.
   *
   * @param membership adhésion
   * @throws com.taskora.board.domain.exception.ConflictException déjà membre
   */
  void insert(Membership membership);

  /**
   * Verrouille l'adhésion visée ({@code FOR UPDATE}).
   *
   * @param board tableau
   * @param user membre
   * @return adhésion, ou vide
   */
  Optional<Membership> lock(BoardId board, UserId user);

  /**
   * Change le rôle si la ligne n'est pas celle du propriétaire ({@code AND role <> 'owner'}).
   *
   * @param board tableau
   * @param user membre
   * @param role nouveau rôle
   * @return vrai si une ligne a changé
   */
  boolean updateRoleUnlessOwner(BoardId board, UserId user, Role role);

  /**
   * Supprime les assignations du membre sur les cartes du tableau, puis l'adhésion.
   *
   * @param board tableau
   * @param user membre
   */
  void removeWithAssignments(BoardId board, UserId user);

  /**
   * Bascule le favori de l'appelant.
   *
   * @param board tableau
   * @param user membre
   * @return nouvel état
   */
  boolean toggleFavorite(BoardId board, UserId user);

  /**
   * Membres d'un tableau, par id d'adhésion.
   *
   * @param board tableau
   * @return comptes membres
   */
  List<UserId> memberIds(BoardId board);
}
