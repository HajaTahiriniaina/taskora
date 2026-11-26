package com.taskora.identity.application.port.out;

import java.util.function.Supplier;

/** Transaction. */
public interface UnitOfWorkPort {
  <T> T inTransaction(Supplier<T> work);
}
