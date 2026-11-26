package com.taskora.identity.application.port.in;

/** Identifiants de connexion, tels que saisis. */
public record LoginCommand(String email, String password) {}
