import { create } from 'zustand';

interface PhoneLayerState {
  /** Відкриті шари, що живуть локальним станом компонентів: id → як закрити. */
  layers: Record<string, () => void>;
  openLayer: (id: string, close: () => void) => void;
  closeLayer: (id: string) => void;
}

export const usePhoneLayerStore = create<PhoneLayerState>((set) => ({
  layers: {},
  openLayer: (id, close) => set((s) => ({ layers: { ...s.layers, [id]: close } })),
  closeLayer: (id) =>
    set((s) => {
      if (!(id in s.layers)) return s;
      const layers = { ...s.layers };
      delete layers[id];
      return { layers };
    }),
}));

/** Реєструє локальний шар у стеку системного BACK на час, поки він відкритий. */
export const registerPhoneLayer = (id: string, open: boolean, close: () => void): (() => void) => {
  const store = usePhoneLayerStore.getState();
  if (open) store.openLayer(id, close);
  else store.closeLayer(id);
  return () => usePhoneLayerStore.getState().closeLayer(id);
};
