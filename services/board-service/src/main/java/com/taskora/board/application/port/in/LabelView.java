package com.taskora.board.application.port.in;

/**
 * Étiquette (schéma {@code Label}).
 *
 * @param id étiquette
 * @param name nom
 * @param color couleur
 */
public record LabelView(int id, String name, String color) {}
