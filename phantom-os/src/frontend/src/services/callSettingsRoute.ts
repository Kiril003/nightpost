export const CALL_SETTINGS_TAB = 'call-settings';

export const openCallSettings = (): boolean =>
  Boolean((window as unknown as { __phantomOpenTab?: (key: string) => boolean }).__phantomOpenTab?.(CALL_SETTINGS_TAB));
