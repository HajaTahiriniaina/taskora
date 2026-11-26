package com.taskora.identity.application.port.out;

import java.util.Map;

/** Jeton dont la signature et les dates sont valides. */
public record VerifiedToken(String username, Map<String, Object> claims) {}
