package com.taskora.board.application.service;

import com.taskora.board.application.port.in.AddMemberResult;
import com.taskora.board.application.port.in.AddMemberUseCase;
import com.taskora.board.application.port.in.CancelInvitationUseCase;
import com.taskora.board.application.port.in.ChangeMemberRoleUseCase;
import com.taskora.board.application.port.in.MemberView;
import com.taskora.board.application.port.in.RemoveMemberUseCase;
import com.taskora.board.domain.model.BoardId;
import com.taskora.board.domain.model.UserId;

/** Membres et invitations (SC-BOARD-009 à 017). Squelette. */
public class MembershipService
    implements AddMemberUseCase,
        ChangeMemberRoleUseCase,
        RemoveMemberUseCase,
        CancelInvitationUseCase {

  /** Crée le service (dépendances ajoutées par BackCodeur). */
  public MembershipService() {
    // squelette
  }

  /** {@inheritDoc} */
  @Override
  public AddMemberResult add(UserId caller, BoardId board, byte[] rawBody) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /** {@inheritDoc} */
  @Override
  public MemberView changeRole(UserId caller, BoardId board, UserId target, byte[] rawBody) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /** {@inheritDoc} */
  @Override
  public void remove(UserId caller, BoardId board, UserId target) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /** {@inheritDoc} */
  @Override
  public void cancel(UserId caller, BoardId board, int invitationId) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
