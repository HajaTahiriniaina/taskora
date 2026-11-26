package com.taskora.identity.application.port.in;

/**
 * Champs de l'inscription, déjà contrôlés comme chaînes JSON par l'adapter web (E17) ; null =
 * absent.
 */
public record RegisterUserCommand(String name, String email, String password) {}
