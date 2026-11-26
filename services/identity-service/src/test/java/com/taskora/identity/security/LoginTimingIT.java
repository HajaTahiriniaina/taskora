package com.taskora.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.identity.support.ApiTestBase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** SC-IDENT-008 : pas d'oracle temporel flagrant entre e-mail inconnu et mauvais mot de passe (coût 13). */
class LoginTimingIT extends ApiTestBase {
  private static final int RUNS = 30;

  @DynamicPropertySource
  static void cost13(DynamicPropertyRegistry r) {
    r.add("identity.password.bcrypt-cost", () -> "13");
  }

  private long timeLogin(String email) throws Exception {
    long t0 = System.nanoTime();
    int status = login(email, "mauvaismdp").getResponse().getStatus();
    long t = System.nanoTime() - t0;
    assertThat(status).isEqualTo(401);
    return t;
  }

  private static double median(List<Long> v) {
    List<Long> s = new ArrayList<>(v);
    Collections.sort(s);
    return (s.get(s.size() / 2) + s.get((s.size() - 1) / 2)) / 2.0;
  }

  @Test
  @DisplayName("SC-IDENT-008 — 30 essais e-mail inconnu vs mauvais mot de passe : écart des médianes < 50 %")
  void comparableTimes() throws Exception {
    registeredToken("Camille", "camille@exemple.fr", "motdepasse");
    assertThat(storedHash("camille@exemple.fr")).startsWith("$2y$13$");
    for (int i = 0; i < 3; i++) {
      timeLogin("camille@exemple.fr");
      timeLogin("inconnu@exemple.fr");
    }
    List<Long> wrong = new ArrayList<>();
    List<Long> unknown = new ArrayList<>();
    for (int i = 0; i < RUNS; i++) {
      wrong.add(timeLogin("camille@exemple.fr"));
      unknown.add(timeLogin("inconnu-" + i + "@exemple.fr"));
    }
    double a = median(wrong);
    double b = median(unknown);
    assertThat(Math.abs(a - b) / Math.max(a, b)).as("médianes %s ns / %s ns", a, b).isLessThan(0.5);
  }
}
