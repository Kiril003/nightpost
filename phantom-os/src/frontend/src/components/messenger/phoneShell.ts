/**
 * Телефон — це не «вузький екран», а інша машина: у WebView усередині APK міст
 * віддає 404 на relay|ice|call|files|directory. Тому мірою тут є наявність
 * мосту, а не ширина: на вузькому вікні ПК дзвінки працюють і ховати їх нема за що.
 */
export const isPhoneShell = (): boolean =>
  typeof window !== 'undefined' &&
  Boolean((window as unknown as { PhantomBridge?: unknown }).PhantomBridge);
