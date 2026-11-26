package com.taskora.identity.application.port.in;

/** Représentation publique d'un utilisateur. */
public record UserView(int id, String name, String email, String avatarColor) {}
