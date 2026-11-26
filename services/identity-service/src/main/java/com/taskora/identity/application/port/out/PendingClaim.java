package com.taskora.identity.application.port.out;

/** Message d'outbox dû. */
public record PendingClaim(InvitationClaim claim, int attempts) {}
