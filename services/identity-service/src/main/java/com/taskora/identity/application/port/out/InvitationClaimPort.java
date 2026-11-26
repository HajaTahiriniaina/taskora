package com.taskora.identity.application.port.out;

/** Client de POST /internal/invitations/claim. */
public interface InvitationClaimPort {
  /**
   * Appelle POST /internal/invitations/claim de Symfony (A10).
   *
   * @param claim utilisateur et e-mail dont les invitations sont à consommer
   * @return livré, rejouable (404, 5xx, réseau) ou définitif (400, 401, 409)
   */
  ClaimOutcome claim(InvitationClaim claim);
}
