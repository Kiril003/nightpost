import { useEffect, useState } from 'react';
import { iceConfig, lastKnownIce, type IceConfig } from '../services/iceRoads';

/** Скільки разів перепитати вузол, поки слухач сітки не встане. */
const INBOUND_TRIES = 12;

/** Крок опитування: сокет біндиться за мить, служба сітки — за секунду-дві. */
const INBOUND_STEP_MS = 1_500;

/**
 * Дороги ICE — і, головне, чи приймає цей вузол вхідні дзвінки.
 *
 * Питати ОДИН раз за монтування було вадою, і вадою тихою. `inbound` на
 * телефоні означає «слухач сітки вже підняв сокет»; сокет встає після того, як
 * реєстр віддав контакти, служба сітки піднялась і корутина стрибнула на IO —
 * тобто ПІЗНІШЕ, ніж веб встигає спитати. Одна відповідь `false`, узята в ту
 * мить, лишалась на весь сеанс: шапка розмови ховала кнопки дзвінка зовсім, і
 * подзвонити не було звідки, доки застосунок не перезапустять у вдалу мить.
 *
 * Тепер питаємо, поки не почуємо «так», — з межею, бо реєстр без людей не
 * підіймає сітку взагалі, і довбати міст вічно немає за що.
 */
export function useIceRoads(): IceConfig | null {
  const [ice, setIce] = useState<IceConfig | null>(() => lastKnownIce());
  useEffect(() => {
    let alive = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let left = INBOUND_TRIES;
    const ask = () => {
      void iceConfig().then((config) => {
        if (!alive) return;
        setIce(config);
        if (config.inbound === true || left <= 0) return;
        left -= 1;
        timer = setTimeout(ask, INBOUND_STEP_MS);
      });
    };
    ask();
    return () => {
      alive = false;
      if (timer !== undefined) clearTimeout(timer);
    };
  }, []);
  return ice;
}
