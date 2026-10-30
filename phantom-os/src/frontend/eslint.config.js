// ESLint 9 flat config. До цього `npm run lint` просто падав: дев'ятка не
// читає .eslintrc, а eslint.config.* у дереві не було взагалі — тобто ворота
// лінта стояли зачинені й ніхто цього не бачив.
//
// Планка правил навмисно помірна. Помилка — лише те, що ловить справжню ваду:
// зламаний порядок хуків, звернення до неоголошеного імені, забутий проміс.
// Усе інше — попередження: інакше --max-warnings 0 перетворив би перший же
// прогін на 3000 «помилок», і ворота знову стояли б зачинені, тільки тепер
// голосно.
//
// Нічого не виправлено автоматично. У пам'яті проєкту лежить випадок, коли
// `--fix` на exhaustive-deps зламав робочий код: правило дописує залежності,
// не розуміючи, що ефект навмисно стежить лише за частиною з них.

import js from '@eslint/js';
import globals from 'globals';
import tsParser from '@typescript-eslint/parser';
import tsPlugin from '@typescript-eslint/eslint-plugin';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';

/** Правила, спільні для всього TypeScript. */
const tsRules = {
  // ── Помилки: справжні вади ────────────────────────────────────────────
  // Порядок хуків — єдине правило React, порушення якого гарантовано ламає
  // рантайм, а не стиль.
  'react-hooks/rules-of-hooks': 'error',
  // Оголошена й не використана змінна — або залишок від видаленої гілки,
  // або друкарська помилка в імені. Аргументи й `catch (e)` не рахуємо:
  // там невикористаність — норма сигнатури.
  'no-unused-vars': 'off',
  '@typescript-eslint/no-unused-vars': [
    'error',
    {
      vars: 'all',
      varsIgnorePattern: '^_',
      args: 'none',
      caughtErrors: 'none',
      ignoreRestSiblings: true,
    },
  ],

  // ── Попередження: варте розбору, але не блокує ────────────────────────
  'react-hooks/exhaustive-deps': 'warn',
  'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
  '@typescript-eslint/no-explicit-any': 'warn',
  '@typescript-eslint/no-empty-object-type': 'warn',
  '@typescript-eslint/no-unsafe-function-type': 'warn',
  '@typescript-eslint/no-wrapper-object-types': 'warn',
  'no-empty': ['warn', { allowEmptyCatch: true }],
  'no-constant-condition': ['warn', { checkLoops: false }],

  // TypeScript уже перевіряє існування імен, і робить це точніше: `no-undef`
  // на .ts дає хибні спрацювання на типах і на просторі імен JSX. Лишаємо
  // його там, де він єдиний сторож, — у звичайному JS (нижче).
  'no-undef': 'off',
};

export default [
  {
    // Мертві `eslint-disable` — прибирання, а не вада, тож попередження.
    // Помилкою вони зробили б `npm run lint` червоним на 50 коментарях,
    // що лишились від конфігу, якого в дереві вже нема.
    linterOptions: { reportUnusedDisableDirectives: 'warn' },
  },
  {
    ignores: [
      'dist/**',
      'dist-phone/**',
      'node_modules/**',
      'playwright-report/**',
      'test-results/**',
      'src-tauri/target/**',
      'public/**',
      'coverage/**',
    ],
  },

  js.configs.recommended,

  // ── Продуктовий код: типозалежний лінт ────────────────────────────────
  // `projectService` вмикає правила, яким потрібен тип, — насамперед
  // no-floating-promises: забутий `await` тут не теоретичний, а той самий
  // клас «мовчки нічого не робить», що коштував продукту найдорожче.
  {
    files: ['src/**/*.{ts,tsx}'],
    languageOptions: {
      parser: tsParser,
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
        ecmaFeatures: { jsx: true },
      },
      globals: { ...globals.browser, ...globals.node },
    },
    plugins: {
      '@typescript-eslint': tsPlugin,
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...tsRules,
      '@typescript-eslint/no-floating-promises': 'error',
      '@typescript-eslint/await-thenable': 'warn',
      '@typescript-eslint/no-misused-promises': [
        'warn',
        { checksVoidReturn: false },
      ],
    },
  },

  // Тести: та сама планка, мінус правило Fast Refresh (у тестових файлах
  // експортують помічники поруч із компонентами навмисно).
  {
    files: ['src/**/__tests__/**/*.{ts,tsx}', 'src/**/*.test.{ts,tsx}'],
    languageOptions: { globals: { ...globals.node } },
    rules: {
      'react-refresh/only-export-components': 'off',
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },

  // ── Периферія: e2e, конфіги ───────────────────────────────────────────
  // Ці файли не входять у tsconfig проєкту, тому типозалежних правил тут
  // немає — інакше парсер відмовився б їх читати.
  {
    files: ['e2e/**/*.{ts,tsx}', '*.config.{ts,js}', 'playwright.config.ts'],
    languageOptions: {
      parser: tsParser,
      parserOptions: { ecmaFeatures: { jsx: true } },
      globals: { ...globals.node, ...globals.browser },
    },
    plugins: {
      '@typescript-eslint': tsPlugin,
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...tsRules,
      'react-hooks/rules-of-hooks': 'off',
      'react-hooks/exhaustive-deps': 'off',
      'react-refresh/only-export-components': 'off',
    },
  },

  // ── Разові зонди в корені (_*.mjs) ────────────────────────────────────
  // Вони в дереві й під git, тож міряємо їх чесно — але це скрипти Node,
  // і саме тут `no-undef` єдиний, хто ловить одрук в імені.
  //
  // Глобали браузера тут потрібні поряд з Node: це сценарії Playwright, і
  // тіла `page.evaluate(...)` виконуються у сторінці. Без цього правило
  // давало 131 «помилку» на `window`/`document`/`localStorage` — усі до
  // одної хибні. Перше число, яке дає новий лінт, не має бути брехнею.
  {
    files: ['*.mjs', 'scripts/**/*.{js,mjs}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: { ...globals.node, ...globals.browser },
    },
    rules: {
      'no-undef': 'error',
      'no-unused-vars': ['error', { vars: 'all', args: 'none', caughtErrors: 'none' }],
      'no-empty': ['warn', { allowEmptyCatch: true }],
    },
  },

  // ── AudioWorklet ──────────────────────────────────────────────────────
  // Окремий глобальний контекст: ні `window`, ні `document`, зате
  // `registerProcessor`, `AudioWorkletProcessor`, `sampleRate`.
  {
    files: ['src/**/*.worklet.js'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: {
        ...globals.worker,
        AudioWorkletProcessor: 'readonly',
        registerProcessor: 'readonly',
        sampleRate: 'readonly',
        currentTime: 'readonly',
        currentFrame: 'readonly',
      },
    },
    rules: {
      'no-undef': 'error',
      'no-unused-vars': ['error', { vars: 'all', args: 'none', caughtErrors: 'none' }],
    },
  },
];
