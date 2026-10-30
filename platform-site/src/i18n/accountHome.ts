import type { Lang } from './types';

export interface HomeCopy {
  account: string;
  signOut: string;
  since: (d: string) => string;
  stageEmpty: string;
  requestsPhone: string;
  connectPhone: string;
  scanInApp: string;
  phoneConnected: string;
  newCode: string;
  stageFailed: string;
  stageNotOpen: string;
  createPlace: string;
  retry: string;
  openBuilder: string;
  appTitle: string;
  build: (n: number | string) => string;
  version: string;
  released: string;
  size: string;
  copy: string;
  copyChecksum: string;
  licence: string;
  needLicence: string;
  copied: string;
  download: string;
  preparing: string;
  downloadFailed: string;
  noBuild: string;
  downloads: string;
  places: string;
  published: string;
  draft: string;
  updated: (d: string) => string;
  devices: string;
  allDevices: string;
  thisBrowser: string;
  via: {
    email: string;
    qr: string;
  };
  signedIn: (d: string) => string;
  devicesFailed: string;
  devicesEmpty: string;
  signOutDevice: string;
}

const en: HomeCopy = {
  account: 'You',
  signOut: 'Sign out',
  since: (d) => `Member since ${d}`,
  stageEmpty: 'No places yet',
  requestsPhone: 'Requests on your phone',
  connectPhone: 'Connect phone',
  scanInApp: 'Scan in Nightpost',
  phoneConnected: 'Phone connected',
  newCode: 'New code',
  stageFailed: "Couldn't load places",
  stageNotOpen: 'Not open yet',
  createPlace: 'Create a place',
  retry: 'Retry',
  openBuilder: 'Open Builder',
  appTitle: 'Nightpost for Android',
  build: (n) => `Build ${n}`,
  version: 'Version',
  released: 'Released',
  size: 'Size',
  copy: 'Copy',
  copyChecksum: 'Copy checksum',
  licence: 'Licence',
  needLicence: 'This build needs a licence.',
  copied: 'Copied',
  download: 'Download APK',
  preparing: 'Preparing…',
  downloadFailed: 'Download unavailable',
  noBuild: 'No Android build yet',
  downloads: 'Downloads',
  places: 'Places',
  published: 'Published',
  draft: 'Draft',
  updated: (d) => `Updated ${d}`,
  devices: 'Devices',
  allDevices: 'All devices',
  thisBrowser: 'this device',
  via: {
    email: 'Email',
    qr: 'QR sign-in',
  },
  signedIn: (d) => `Signed in ${d}`,
  devicesFailed: "Couldn't load devices",
  devicesEmpty: 'No devices yet',
  signOutDevice: 'Revoke',
};

const uk: HomeCopy = {
  account: 'Я',
  signOut: 'Вийти',
  since: (d) => `З нами з ${d}`,
  stageEmpty: 'Ще немає місць',
  requestsPhone: 'Запити на телефон',
  connectPhone: "Під'єднати телефон",
  scanInApp: 'Скануйте в Nightpost',
  phoneConnected: "Телефон під'єднано",
  newCode: 'Новий код',
  stageFailed: 'Не вдалося завантажити місця',
  stageNotOpen: 'Ще не відкрито',
  createPlace: 'Створити місце',
  retry: 'Спробувати знову',
  openBuilder: 'Відкрити конструктор',
  appTitle: 'Nightpost для Android',
  build: (n) => `Збірка ${n}`,
  version: 'Версія',
  released: 'Випуск',
  size: 'Розмір',
  copy: 'Скопіювати',
  copyChecksum: 'Копіювати контрольну суму',
  licence: 'Ліцензія',
  needLicence: 'Ця збірка потребує ліцензії.',
  copied: 'Скопійовано',
  download: 'Завантажити APK',
  preparing: 'Готуємо…',
  downloadFailed: 'Завантаження недоступне',
  noBuild: 'Збірки для Android ще немає',
  downloads: 'Завантаження',
  places: 'Місця',
  published: 'Опубліковано',
  draft: 'Чернетка',
  updated: (d) => `Оновлено ${d}`,
  devices: 'Пристрої',
  allDevices: 'Усі пристрої',
  thisBrowser: 'цей пристрій',
  via: {
    email: 'Пошта',
    qr: 'Вхід за QR',
  },
  signedIn: (d) => `Вхід ${d}`,
  devicesFailed: 'Не вдалося завантажити пристрої',
  devicesEmpty: 'Ще немає пристроїв',
  signOutDevice: 'Відкликати',
};

export const homeCopy: Record<Lang, HomeCopy> = { uk, en };
