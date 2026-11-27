package com.taskora.board;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Point d'entrée de board-service (phase 3, B-ADR-12). */
@SpringBootApplication
public class BoardServiceApplication {

  /**
   * Démarre le service.
   *
   * @param args arguments de la ligne de commande (dont {@code --board.verify} et {@code
   *     --board.outbox-drain})
   */
  public static void main(String[] args) {
    SpringApplication.run(BoardServiceApplication.class, args);
  }
}
