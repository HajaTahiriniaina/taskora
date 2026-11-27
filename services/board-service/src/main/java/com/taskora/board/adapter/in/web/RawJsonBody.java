package com.taskora.board.adapter.in.web;

/**
 * Octets du corps, lus sans analyse par l'adapter web (B-ADR-3 point 3) ; l'analyse est faite par
 * le cas d'utilisation, après la politique d'accès.
 *
 * @param bytes corps brut (vide si absent)
 */
public record RawJsonBody(byte[] bytes) {}
