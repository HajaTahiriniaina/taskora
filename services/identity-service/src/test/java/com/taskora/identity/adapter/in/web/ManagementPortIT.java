package com.taskora.identity.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.identity.IdentityServiceApplication;
import com.taskora.identity.support.MariaDbContainerSupport;
import com.taskora.identity.support.TestClockConfig;
import com.taskora.identity.support.TestKeys;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** SC-IDENT-022 : /actuator/health servi sur le port de management uniquement (non routé par nginx). */
@SpringBootTest(classes = IdentityServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClockConfig.class)
class ManagementPortIT {
  private static final TestKeys KEYS = TestKeys.shared();
  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;
  @LocalManagementPort int managementPort;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    MariaDbContainerSupport.register(r);
    r.add("management.server.port", () -> "0");
    r.add("identity.jwt.private-key-path", () -> KEYS.privatePem().toString());
    r.add("identity.jwt.public-key-path", () -> KEYS.publicPem().toString());
    r.add("identity.jwt.passphrase", KEYS::passphrase);
    r.add("identity.password.bcrypt-cost", () -> "4");
    r.add("identity.outbox.relay.enabled", () -> "false");
  }

  private HttpResponse<String> get(int p, String path) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + p + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @DisplayName("SC-IDENT-022 A13 — application.yml : management sur le port 8091, seul health exposé, aucune trace d'erreur")
  void configuredPorts() {
    org.springframework.beans.factory.config.YamlPropertiesFactoryBean yaml =
        new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
    yaml.setResources(new org.springframework.core.io.ClassPathResource("application.yml"));
    java.util.Properties p = yaml.getObject();
    assertThat(p.getProperty("management.server.port")).isEqualTo("8091");
    assertThat(p.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
    assertThat(p.getProperty("server.error.include-stacktrace")).isEqualTo("never");
    assertThat(p.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
    assertThat(p.getProperty("spring.flyway.schemas")).isEqualTo("identity");
  }

  @Test
  @DisplayName("SC-IDENT-022 — /actuator/health sur le port de management, sans jeton → 200 UP")
  void healthOnManagementPort() throws Exception {
    assertThat(managementPort).isNotEqualTo(port);
    HttpResponse<String> r = get(managementPort, "/actuator/health");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).contains("\"UP\"");
  }

  @Test
  @DisplayName("SC-IDENT-022 — /actuator/health absent du port applicatif")
  void notOnApplicationPort() throws Exception {
    assertThat(get(port, "/actuator/health").statusCode()).isNotEqualTo(200);
  }

  @Test
  @DisplayName("SC-IDENT-022 — seul health est exposé (pas d'env, beans, heapdump…)")
  void onlyHealthExposed() throws Exception {
    for (String ep : new String[] {"/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/configprops"}) {
      assertThat(get(managementPort, ep).statusCode()).as(ep).isNotEqualTo(200);
    }
  }
}
