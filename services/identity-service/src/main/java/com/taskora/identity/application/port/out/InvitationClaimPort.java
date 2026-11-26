package com.taskora.identity.application.port.out;

/** Client de POST /internal/invitations/claim. */
public interface InvitationClaimPort {
  ClaimOutcome claim(InvitationClaim claim);
}
