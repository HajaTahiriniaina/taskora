package com.taskora.identity.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Horloge réglable injectée à la place de l'horloge système. */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {
  public static final MutableClock CLOCK = MutableClock.startingNow();

  @Bean
  @Primary
  public MutableClock testClock() {
    return CLOCK;
  }
}
