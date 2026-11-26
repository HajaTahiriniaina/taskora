package com.taskora.identity.application.port.out;

import com.taskora.identity.domain.model.User;

/** Projection vers taskora.user (A9). */
public interface LegacyUserProjectionPort {
  /**
   * Écrit le compte dans taskora.user (id explicite, jamais de DELETE), dans la transaction
   * courante (A9).
   *
   * @param user état à projeter
   * @throws com.taskora.identity.domain.exception.EmailAlreadyUsedException si l'e-mail y
   *     appartient déjà à une autre ligne
   */
  void upsert(User user);
}
