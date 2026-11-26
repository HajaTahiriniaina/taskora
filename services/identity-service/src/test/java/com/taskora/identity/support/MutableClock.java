package com.taskora.identity.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Horloge de test réglable (pas de sleep dans les tests). */
public final class MutableClock extends Clock {
  private volatile Instant now;
  private final ZoneId zone;

  public MutableClock(Instant start) {
    this(start, ZoneOffset.UTC);
  }

  private MutableClock(Instant start, ZoneId zone) {
    this.now = start;
    this.zone = zone;
  }

  /** Horloge à l'instant présent, tronqué à la seconde. */
  public static MutableClock startingNow() {
    return new MutableClock(Instant.ofEpochSecond(Instant.now().getEpochSecond()));
  }

  public void set(Instant instant) {
    this.now = instant;
  }

  public void advance(Duration duration) {
    this.now = now.plus(duration);
  }

  public long epochSecond() {
    return now.getEpochSecond();
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  @Override
  public Clock withZone(ZoneId newZone) {
    MutableClock parent = this;
    return new Clock() {
      @Override
      public ZoneId getZone() {
        return newZone;
      }

      @Override
      public Clock withZone(ZoneId z) {
        return parent.withZone(z);
      }

      @Override
      public Instant instant() {
        return parent.instant();
      }
    };
  }

  @Override
  public Instant instant() {
    return now;
  }
}
