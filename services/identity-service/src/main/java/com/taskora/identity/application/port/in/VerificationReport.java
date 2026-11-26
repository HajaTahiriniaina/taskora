package com.taskora.identity.application.port.in;

import java.util.List;
import java.util.Map;

/** Rapport de contrôle : nombres et empreintes uniquement. */
public record VerificationReport(
    boolean consistent, List<String> failedChecks, Map<String, String> metrics) {}
