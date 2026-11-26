package com.taskora.identity.application.port.out;

import com.taskora.identity.application.port.in.ImportReport;
import java.util.Map;

/** Lecture de taskora.user pour la reprise. */
public interface LegacyUserSourcePort {
  /**
   * Compte les groupes de comptes source dont {@code LOWER(TRIM(email))} se répète.
   *
   * @return nombre de groupes en doublon (0 attendu)
   */
  int countNormalizedEmailDuplicates();

  /**
   * Copie la source dans la cible (INSERT … ON DUPLICATE KEY UPDATE par id), jamais de DELETE.
   *
   * @return rapport chiffré
   * @throws IllegalStateException si un e-mail normalisé existe déjà sous un autre id (A18)
   */
  ImportReport copyAllIntoIdentity();

  /**
   * Mesures de la source et valeurs attendues des contrôles croisés.
   *
   * @return nom du contrôle vers valeur (nombres et empreintes seulement)
   */
  Map<String, String> sourceMetrics();

  /**
   * Mesures de la cible, avec les mêmes clés que {@link #sourceMetrics()}.
   *
   * @return nom du contrôle vers valeur (nombres et empreintes seulement)
   */
  Map<String, String> targetMetrics();
}
