package com.taskora.board.application.port.out;

import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.Invitation;
import com.taskora.board.domain.model.UserId;
import java.time.Instant;
import java.util.List;

/** Écritures sur {@code invitation}. */
public interface InvitationRepositoryPort {

  /**
   * Insère une invitation ; violation de l'index unique → conflit, jamais 500.
   *
   * @param board tableau
   * @param email e-mail normalisé
   * @param invitedBy inviteur
   * @param at instant
   * @throws com.taskora.board.domain.exception.ConflictException invitation déjà en attente
   */
  void insert(BoardId board, String email, UserId invitedBy, Instant at);

  /**
   * Supprime une invitation du tableau.
   *
   * @param board tableau
   * @param invitationId invitation
   * @return vrai si supprimée (faux : inexistante ou d'un autre tableau)
   */
  boolean deleteFromBoard(BoardId board, int invitationId);

  /**
   * Invitations d'un e-mail (collation de la colonne), verrouillées.
   *
   * @param email e-mail
   * @return invitations
   */
  List<Invitation> lockByEmail(String email);

  /**
   * Supprime une invitation.
   *
   * @param invitationId invitation
   */
  void delete(int invitationId);
}
