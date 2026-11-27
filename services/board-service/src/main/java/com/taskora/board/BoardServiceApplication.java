package com.taskora.board;

import java.util.Arrays;
import java.util.stream.Stream;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** Point d'entrée de board-service (phase 3, B-ADR-12). */
// Pas d'utilisateur en mémoire ni de mot de passe généré : l'authentification est le filtre JWT.
@SpringBootApplication(
    excludeName =
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration")
public class BoardServiceApplication {

  /**
   * Démarre le service ; avec {@code --board.verify} ou {@code --board.outbox-drain}, exécute la
   * commande sans serveur web ni relais planifié et sort avec son code (0, 1 ou 2).
   *
   * @param args arguments de la ligne de commande (dont {@code --board.verify} et {@code
   *     --board.outbox-drain})
   */
  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(BoardServiceApplication.class);
    boolean command =
        Arrays.stream(args)
            .anyMatch(a -> a.startsWith("--board.verify") || a.startsWith("--board.outbox-drain"));
    if (!command) {
      app.run(args);
      return;
    }
    app.setWebApplicationType(WebApplicationType.NONE);
    String[] commandArgs =
        Stream.concat(Arrays.stream(args), Stream.of("--board.outbox.relay.enabled=false"))
            .toArray(String[]::new);
    ConfigurableApplicationContext context = app.run(commandArgs);
    System.exit(SpringApplication.exit(context));
  }
}
