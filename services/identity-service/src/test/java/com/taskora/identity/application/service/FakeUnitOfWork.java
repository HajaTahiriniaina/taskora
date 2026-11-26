package com.taskora.identity.application.service;

import com.taskora.identity.application.port.out.UnitOfWorkPort;
import java.util.function.Supplier;

/** Unité de travail factice : sait si une transaction est ouverte et compte les transactions. */
final class FakeUnitOfWork implements UnitOfWorkPort {
  boolean active;
  int transactions;

  @Override
  public <T> T inTransaction(Supplier<T> work) {
    if (active) {
      throw new AssertionError("transaction imbriquée inattendue");
    }
    active = true;
    transactions++;
    try {
      return work.get();
    } finally {
      active = false;
    }
  }

  void assertActive(String what) {
    if (!active) {
      throw new AssertionError(what + " doit être appelé DANS l'unité de travail");
    }
  }

  void assertInactive(String what) {
    if (active) {
      throw new AssertionError(what + " doit être appelé APRÈS l'unité de travail");
    }
  }
}
