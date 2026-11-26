package com.taskora.identity;

import java.util.Arrays;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** Point d'entrée d'identity-service. */
// Pas d'utilisateur en mémoire ni de mot de passe généré : l'authentification est le filtre JWT.
@SpringBootApplication(
    excludeName = "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration")
public class IdentityServiceApplication {

  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(IdentityServiceApplication.class);
    boolean command =
        Arrays.stream(args)
            .anyMatch(
                a ->
                    a.startsWith("--identity.legacy-import")
                        || a.startsWith("--identity.outbox-drain"));
    if (!command) {
      app.run(args);
      return;
    }
    // Commande ponctuelle (reprise, vidage de l'outbox) : sans serveur web ni relais planifié.
    app.setWebApplicationType(WebApplicationType.NONE);
    app.setDefaultProperties(Map.of("identity.outbox.relay.enabled", "false"));
    ConfigurableApplicationContext context = app.run(args);
    System.exit(SpringApplication.exit(context));
  }
}
