package com.taskora.board.application.port.out;

import java.util.function.Supplier;

/** Transaction locale MariaDB ; rejouée en cas d'interblocage (B-ADR-4 point 4). */
public interface UnitOfWorkPort {

  /**
   * Exécute un travail dans une transaction.
   *
   * @param <T> résultat
   * @param work travail
   * @return résultat
   */
  <T> T inTransaction(Supplier<T> work);
}
