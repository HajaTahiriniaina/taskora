package com.taskora.identity.application.service;

import com.taskora.identity.application.port.in.ImportLegacyUsersUseCase;
import com.taskora.identity.application.port.in.ImportReport;
import com.taskora.identity.application.port.in.VerificationReport;
import com.taskora.identity.application.port.out.LegacyUserSourcePort;
import com.taskora.identity.application.port.out.UnitOfWorkPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Reprise idempotente (A14, identity-migration.md §3-4). */
public class LegacyImportService implements ImportLegacyUsersUseCase {
  static final String DUPLICATES_CHECK = "normalizedEmailDuplicates";
  static final String ABSENT = "absent";

  private final LegacyUserSourcePort source;
  private final UnitOfWorkPort unitOfWork;

  /**
   * Assemble le cas d'utilisation.
   *
   * @param source accès à la source et à la cible de la reprise
   * @param unitOfWork transaction de la copie
   */
  public LegacyImportService(LegacyUserSourcePort source, UnitOfWorkPort unitOfWork) {
    this.source = source;
    this.unitOfWork = unitOfWork;
  }

  @Override
  public ImportReport importAll() {
    int duplicates = source.countNormalizedEmailDuplicates();
    if (duplicates > 0) {
      // Aucun e-mail dans le message : seulement le nombre de groupes en doublon.
      throw new IllegalStateException(
          "Reprise annulée avant toute écriture : "
              + duplicates
              + " groupe(s) d'e-mails en doublon après normalisation dans la source");
    }
    return unitOfWork.inTransaction(source::copyAllIntoIdentity);
  }

  @Override
  public VerificationReport verify() {
    Map<String, String> sourceMetrics = source.sourceMetrics();
    Map<String, String> targetMetrics = source.targetMetrics();
    int duplicates = source.countNormalizedEmailDuplicates();

    List<String> failed = new ArrayList<>();
    if (duplicates > 0) {
      failed.add(DUPLICATES_CHECK);
    }
    Map<String, String> metrics = new LinkedHashMap<>();
    metrics.put("source." + DUPLICATES_CHECK, String.valueOf(duplicates));
    for (String key : new TreeSet<>(union(sourceMetrics, targetMetrics))) {
      String expected = sourceMetrics.get(key);
      String actual = targetMetrics.get(key);
      metrics.put("source." + key, Objects.toString(expected, ABSENT));
      metrics.put("target." + key, Objects.toString(actual, ABSENT));
      if (!Objects.equals(expected, actual)) {
        failed.add(key);
      }
    }
    return new VerificationReport(failed.isEmpty(), List.copyOf(failed), Map.copyOf(metrics));
  }

  private static TreeSet<String> union(Map<String, String> a, Map<String, String> b) {
    TreeSet<String> keys = new TreeSet<>(a.keySet());
    keys.addAll(b.keySet());
    return keys;
  }
}
