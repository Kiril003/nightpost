import { useEffect, useRef } from 'react';

export interface BackLayer {
  open: boolean;
  close: () => void;
}

/**
 * Системний BACK на телефоні закриває верхній шар, а не викидає з месенджера.
 *
 * Хост дивиться на `webView.canGoBack()`; веб перемикав екрани станом і не
 * лишав в історії нічого — тож із розмови BACK вів на домашній екран PHANTOM
 * (кадр 04b, A52, 06.09). Кожен відкритий шар кладе запис в історію; popstate
 * знімає верхній відкритий; шар, закритий дотиком, забирає свій запис сам,
 * інакше наступний BACK зʼїв би порожній запис і нічого не змінив.
 */
export function usePhoneBackStack(layers: BackLayer[], enabled: boolean): void {
  const depth = useRef(0);
  const swallow = useRef(0);
  const latest = useRef(layers);
  latest.current = layers;
  const openCount = layers.filter((l) => l.open).length;

  useEffect(() => {
    if (!enabled) return;
    if (openCount > depth.current) {
      for (let i = depth.current; i < openCount; i++) {
        window.history.pushState({ phantomLayer: i + 1 }, '');
      }
      depth.current = openCount;
    } else if (openCount < depth.current) {
      const extra = depth.current - openCount;
      swallow.current += extra;
      depth.current = openCount;
      window.history.go(-extra);
    }
  }, [openCount, enabled]);

  /*
   * BACK, яким кермує телефон, а не історія браузера. Шар, відкритий без
   * дотику в самій сторінці (тап по сповіщенню, «відкрий розмову» від рідного
   * боку), кладе запис в історію без жесту користувача — і Chromium такий
   * запис для «Назад» пропускає: `canGoBack()` каже false, і BACK вів не в
   * список, а геть із застосунку (A52, 02.10). Тож хост спершу питає тут:
   * true — верхній шар закрито, false — закривати нічого.
   */
  useEffect(() => {
    if (!enabled) return;
    const w = window as unknown as { __phantomBack?: () => boolean };
    const back = (): boolean => {
      const open = latest.current.filter((l) => l.open);
      const top = open[open.length - 1];
      if (!top) return false;
      top.close();
      return true;
    };
    w.__phantomBack = back;
    return () => {
      if (w.__phantomBack === back) delete w.__phantomBack;
    };
  }, [enabled]);

  useEffect(() => {
    if (!enabled) return;
    const onPop = () => {
      if (swallow.current > 0) {
        swallow.current -= 1;
        return;
      }
      if (depth.current === 0) return;
      depth.current -= 1;
      const open = latest.current.filter((l) => l.open);
      open[open.length - 1]?.close();
    };
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, [enabled]);
}
