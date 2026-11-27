package com.taskora.board.application.port.in;

/**
 * Champ personnalisé (schéma {@code CustomField}).
 *
 * @param id champ
 * @param name nom
 * @param type type
 * @param optionsJson options relues telles qu'écrites (texte JSON, R5)
 * @param showOnCard affiché sur la carte
 * @param position position
 */
public record CustomFieldView(
    int id, String name, String type, String optionsJson, boolean showOnCard, int position) {}
