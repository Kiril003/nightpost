export const VOICE_TEXT_KEY = 'np.voiceText.v1';

export const loadVoiceText = (): boolean => {
  try {
    return localStorage.getItem(VOICE_TEXT_KEY) !== 'off';
  } catch {
    return true;
  }
};

export const saveVoiceText = (on: boolean): void => {
  try {
    localStorage.setItem(VOICE_TEXT_KEY, on ? 'on' : 'off');
  } catch {
    /* без сховища — лише для цієї нотатки */
  }
};
