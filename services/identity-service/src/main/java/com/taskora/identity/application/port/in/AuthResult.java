package com.taskora.identity.application.port.in;

/** Jeton et profil. */
public record AuthResult(String token, UserView user) {}
