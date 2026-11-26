package com.taskora.identity.application.port.out;

import com.taskora.identity.domain.model.Email;
import com.taskora.identity.domain.model.UserId;
import java.util.UUID;

/** Demande de consommation des invitations. */
public record InvitationClaim(UUID eventId, UserId userId, Email email) {}
