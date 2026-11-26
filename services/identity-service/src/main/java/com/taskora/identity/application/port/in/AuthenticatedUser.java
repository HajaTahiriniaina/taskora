package com.taskora.identity.application.port.in;

import com.taskora.identity.domain.model.UserId;
import java.util.List;

/** Utilisateur résolu depuis un jeton. */
public record AuthenticatedUser(UserId id, String email, List<String> roles) {}
