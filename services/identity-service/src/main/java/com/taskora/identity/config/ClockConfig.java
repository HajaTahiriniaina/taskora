package com.taskora.identity.config;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Horloge (UTC) et générateur aléatoire injectés dans le domaine et l'application (A6). */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public RandomGenerator randomGenerator() {
    return new SecureRandom();
  }
}
