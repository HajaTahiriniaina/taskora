package com.taskora.identity.application.port.out;

import com.taskora.identity.domain.model.User;

/** Projection vers taskora.user (A9). */
public interface LegacyUserProjectionPort {
  void upsert(User user);
}
