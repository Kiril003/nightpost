import { create } from 'zustand';

export const callCoversScreen = (state: string, minimized: boolean): boolean =>
  state !== 'idle' && !(state === 'active' && minimized);

export const useCallChromeStore = create<{ covers: boolean }>(() => ({ covers: false }));
