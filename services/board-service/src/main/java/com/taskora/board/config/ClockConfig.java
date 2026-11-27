package com.taskora.board.config;

import com.taskora.board.adapter.out.persistence.LegacyClock;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Horloge (UTC) injectée partout (jamais {@code Instant.now()} ni {@code NOW()} SQL). */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public LegacyClock legacyClock(Clock clock, BoardProperties properties) {
    return new LegacyClock(clock, ZoneId.of(properties.legacy().zone()));
  }
}
