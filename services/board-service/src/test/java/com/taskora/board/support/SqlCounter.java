package com.taskora.board.support;

import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Compteur de requêtes SQL (datasource-proxy, B-ADR-8) : la DataSource de board-service est
 * enveloppée ; chaque requête exécutée (lot compris) est comptée.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SqlCounter {
  private static final AtomicInteger QUERIES = new AtomicInteger();
  private static final java.util.List<String> STATEMENTS =
      java.util.Collections.synchronizedList(new java.util.ArrayList<>());

  public static void reset() {
    QUERIES.set(0);
    STATEMENTS.clear();
  }

  /** Textes SQL exécutés depuis le dernier reset. */
  public static java.util.List<String> statements() {
    synchronized (STATEMENTS) {
      return java.util.List.copyOf(STATEMENTS);
    }
  }

  public static int count() {
    return QUERIES.get();
  }

  @Bean
  static BeanPostProcessor countingDataSource() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource ds) {
          QueryExecutionListener listener =
              new QueryExecutionListener() {
                @Override
                public void beforeQuery(
                    net.ttddyy.dsproxy.ExecutionInfo execInfo,
                    java.util.List<net.ttddyy.dsproxy.QueryInfo> queryInfoList) {
                  // rien
                }

                @Override
                public void afterQuery(
                    net.ttddyy.dsproxy.ExecutionInfo execInfo,
                    java.util.List<net.ttddyy.dsproxy.QueryInfo> queryInfoList) {
                  QUERIES.addAndGet(Math.max(1, queryInfoList.size()));
                  queryInfoList.forEach(q -> STATEMENTS.add(q.getQuery()));
                }
              };
          return ProxyDataSourceBuilder.create(ds).name("board-count").listener(listener).build();
        }
        return bean;
      }
    };
  }
}
