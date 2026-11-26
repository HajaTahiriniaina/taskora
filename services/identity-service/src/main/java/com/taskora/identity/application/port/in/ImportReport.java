package com.taskora.identity.application.port.in;

/** Rapport de reprise (nombres uniquement). */
public record ImportReport(int sourceRows, int inserted, int updated, int unchanged, int targetOnly) {}
