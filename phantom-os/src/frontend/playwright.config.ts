import { defineConfig, devices } from '@playwright/test';

/**
 * Два бандли — два сервери, і жодного змішування.
 *
 * `vite preview` подає `dist/` (ПК: з мапами й 57 показовими модалками).
 * Телефонні спеки міряли саме його — тобто розповідали про збірку, якої в
 * APK немає. Тепер phone-спеки живуть в окремому проєкті з власним сервером
 * на `dist-phone/`, а перший тест того проєкту доводить, що поданий бандл
 * справді телефонний.
 */
const PORT = 4173;
const PHONE_PORT = 4174;

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: 'playwright-report' }],
  ],
  use: {
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
    screenshot: 'only-on-failure',
    launchOptions: {
      args: [
        '--use-fake-ui-for-media-stream',
        '--use-fake-device-for-media-stream',
      ],
    },
  },
  projects: [
    {
      name: 'chromium',
      // Телефонні спеки сюди не належать: їхній бандл інший.
      testIgnore: /phone-.*\.spec\.ts$/,
      use: { ...devices['Desktop Chrome'], baseURL: `http://127.0.0.1:${PORT}` },
    },
    {
      name: 'phone',
      testMatch: /phone-.*\.spec\.ts$/,
      use: { ...devices['Desktop Chrome'], baseURL: `http://127.0.0.1:${PHONE_PORT}` },
    },
  ],
  webServer: [
    {
      command: `npm run preview -- --port ${PORT} --host 127.0.0.1`,
      port: PORT,
      reuseExistingServer: !process.env.CI,
      timeout: 60_000,
    },
    {
      // preview:phone збирає dist-phone, якщо його ще немає — інакше
      // десктопний прогін падав би на відсутньому каталозі сусіда.
      command: `npm run preview:phone -- --port ${PHONE_PORT} --host 127.0.0.1`,
      port: PHONE_PORT,
      reuseExistingServer: !process.env.CI,
      timeout: 300_000,
    },
  ],
});
