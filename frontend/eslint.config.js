// @ts-check
const eslint = require('@eslint/js');
const { defineConfig, globalIgnores } = require('eslint/config');
const tseslint = require('typescript-eslint');
const angular = require('angular-eslint');
const perfectionist = require('eslint-plugin-perfectionist');

// Tri perfectionist : natural, asc (docs/architecture/frontend.md §5.2).
const sortOptions = { order: 'asc', type: 'natural' };

module.exports = defineConfig([
  globalIgnores(['dist/', '.angular/', 'node_modules/', 'reports/', 'public/']),
  {
    files: ['**/*.ts'],
    extends: [
      eslint.configs.recommended,
      tseslint.configs.recommended,
      angular.configs.tsRecommended,
    ],
    plugins: { perfectionist },
    processor: angular.processInlineTemplates,
    rules: {
      '@angular-eslint/directive-selector': [
        'error',
        {
          type: 'attribute',
          prefix: 'tk',
          style: 'camelCase',
        },
      ],
      // ligne de base phase 2 : app-root (src/index.html, src/app/app.ts), 1 occurrence.
      '@angular-eslint/component-selector': [
        'warn',
        {
          type: 'element',
          prefix: 'tk',
          style: 'kebab-case',
        },
      ],
      // Correction automatique sans risque : en erreur.
      'perfectionist/sort-imports': ['error', sortOptions],
      'perfectionist/sort-named-imports': ['error', sortOptions],
      'perfectionist/sort-named-exports': ['error', sortOptions],
      'perfectionist/sort-union-types': ['error', sortOptions],
      'perfectionist/sort-intersection-types': ['error', sortOptions],
      // Types seulement, corrigés au lot B1 (recette §4.7) : en erreur.
      'perfectionist/sort-interfaces': ['error', sortOptions],
      'perfectionist/sort-object-types': ['error', sortOptions],
      // Réordonnancement à risque (ordre des inject(), des routes…) : avertissement en phase 2,
      // jamais corrigé automatiquement sur le code existant (lot B4).
      'perfectionist/sort-classes': ['warn', sortOptions],
      'perfectionist/sort-objects': ['warn', sortOptions],
      // ligne de base phase 2 : erreurs existantes non corrigibles automatiquement sans risque.
      '@typescript-eslint/no-explicit-any': 'warn', // ligne de base phase 2
      '@typescript-eslint/no-unused-vars': 'warn', // ligne de base phase 2
      'no-irregular-whitespace': 'warn', // ligne de base phase 2
    },
  },
  {
    files: ['**/*.html'],
    extends: [angular.configs.templateRecommended, angular.configs.templateAccessibility],
    rules: {
      // ligne de base phase 2 : erreurs d'accessibilité existantes, à corriger dans un chantier dédié.
      '@angular-eslint/template/click-events-have-key-events': 'warn', // ligne de base phase 2
      '@angular-eslint/template/interactive-supports-focus': 'warn', // ligne de base phase 2
      '@angular-eslint/template/label-has-associated-control': 'warn', // ligne de base phase 2
      // ligne de base phase 2 : le --fix supprimerait autofocus (changement de comportement).
      '@angular-eslint/template/no-autofocus': 'warn',
    },
  },
]);
