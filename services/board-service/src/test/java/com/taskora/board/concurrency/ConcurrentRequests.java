package com.taskora.board.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Lance des tâches au même instant (barrière) et rend leurs résultats dans l'ordre. */
final class ConcurrentRequests {
  private ConcurrentRequests() {}

  static <T> List<T> together(List<Callable<T>> tasks) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
    CountDownLatch ready = new CountDownLatch(tasks.size());
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<T>> futures = new ArrayList<>();
      for (Callable<T> task : tasks) {
        futures.add(
            pool.submit(() -> {
              ready.countDown();
              go.await();
              return task.call();
            }));
      }
      ready.await(10, TimeUnit.SECONDS);
      go.countDown();
      List<T> out = new ArrayList<>();
      for (Future<T> f : futures) {
        out.add(f.get(60, TimeUnit.SECONDS));
      }
      return out;
    } finally {
      pool.shutdownNow();
    }
  }
}
