package com.taskora.identity.application.port.out;

import com.taskora.identity.application.port.in.ImportReport;
import java.util.Map;

/** Lecture de taskora.user pour la reprise. */
public interface LegacyUserSourcePort {
  int countNormalizedEmailDuplicates();

  ImportReport copyAllIntoIdentity();

  Map<String, String> sourceMetrics();

  Map<String, String> targetMetrics();
}
