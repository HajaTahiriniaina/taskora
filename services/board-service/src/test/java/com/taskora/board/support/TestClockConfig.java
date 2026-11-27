package com.taskora.board.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Horloge réglable injectée à la place de l'horloge système : board-service doit utiliser le bean
 * {@code java.time.Clock} (jamais {@code Instant.now()} ni {@code NOW()} SQL).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {
  public static final MutableClock CLOCK = new MutableClock(Fixture.NOW);

  @Bean
  @Primary
  public MutableClock testClock() {
    return CLOCK;
  }
}
