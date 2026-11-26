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
      '@angular-eslint/component-selector': [
        'error',
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
      // Réordonnancement à risque (ordre des inject(), des routes…) : avertissement en phase 2,
      // jamais corrigé automatiquement sur le code existant.
      'perfectionist/sort-classes': ['warn', sortOptions],
      'perfectionist/sort-objects': ['warn', sortOptions],
      'perfectionist/sort-interfaces': ['warn', sortOptions],
      'perfectionist/sort-object-types': ['warn', sortOptions],
    },
  },
  {
    files: ['**/*.html'],
    extends: [angular.configs.templateRecommended, angular.configs.templateAccessibility],
    rules: {},
  },
]);
