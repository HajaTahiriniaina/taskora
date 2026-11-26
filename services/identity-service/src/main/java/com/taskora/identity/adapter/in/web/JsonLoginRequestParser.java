package com.taskora.identity.adapter.in.web;

import com.taskora.identity.application.port.in.LoginCommand;

/**
 * Parseur du corps de connexion (E19, E20) : forme du corps (vide, non JSON ou non objet → « JSON
 * invalide. ») → type de email → type de password (non chaîne, null compris → « Le champ "x" doit
 * être une chaîne de caractères. ») → présence de email → présence de password (absent ou "" → « Le
 * champ "x" est obligatoire. »). Lève ValidationException. Le Content-Type n'est pas contrôlé.
 */
public final class JsonLoginRequestParser {
  private JsonLoginRequestParser() {}

  public static LoginCommand parse(byte[] body) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
