package com.taskora.identity.application.port.out;

import java.util.function.Supplier;

/** Transaction. */
public interface UnitOfWorkPort {
  /**
   * Exécute le travail dans une transaction ; toute exception l'annule entièrement.
   *
   * @param work travail à exécuter
   * @param <T> type du résultat
   * @return résultat du travail
   */
  <T> T inTransaction(Supplier<T> work);
}
