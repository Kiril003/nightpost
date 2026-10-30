/**
 * Чи можна зараз подзвонити — і якщо ні, ЧОМУ. Одне оголошення на продукт.
 *
 * **Вада, заради якої це існує.** Умова жила лише в `Header.tsx`, приватними
 * константами. Друга кнопка слухавки — у картці співрозмовника
 * (`UserProfileModal`, дії «Аудіо» й «Відео») — не питала нічого: вона просто
 * кидала `phantom:start-call`. А слухач у `MessengerRoot` на порожній адресі
 * робить `return` МОВЧКИ. Тобто в картці палець по слухавці не робив рівно
 * нічого й не казав нічого — саме та вада, яку в шапці полагодили 03.09 і яка
 * поруч, за два дотики, лишилась цілою.
 *
 * Розкласти умову вдруге в другому файлі було б гірше за мовчанку: два
 * оголошення одного контракту розходяться на першій же правці, і тоді дві
 * кнопки слухавки в одному застосунку почнуть казати різне про одне й те саме.
 * Тому умова тут, а поверхні лише питають.
 *
 * `inbound` читається ПО-РІЗНОМУ на телефоні й на ПК, і це не недогляд:
 *  * у WebView усередині APK міст віддає `inbound: true` тільки коли слухач
 *    справді піднявся, тож до того слати нікуди — потрібне саме `=== true`;
 *  * на ПК поля може не бути взагалі (стара збірка вузла), і трактувати
 *    «не сказано» як «не можна» означало б зламати те, що працює. Там
 *    забороняє лише явне `false`.
 */

import { tNow } from '../components/chat/tNow';
import { uk } from '../i18n/messages/uk';

/** Що відомо про дороги дзвінка. Рівно те, що віддає `/messenger/ice`. */
export interface CallRoads {
  inbound?: boolean | null;
}

export interface CallGate {
  /** Кнопка активна. */
  can: boolean;
  /** Чому не активна. Порожньо, коли активна. */
  why: string;
}

/**
 * Три причини — людською мовою: слова «сітка», «вузлова адреса», «1:1»
 * людині нічого не кажуть (власник, 26.09). Кожна все одно каже, що чинити.
 */
/** Вузол ще не сказав, що приймає вхідні. Не вирок — сітка встає за мить. */
export const NO_CALL_ROADS: string = uk['callGate.noRoads'];
/** Дзвінок 1:1; групового дзвінка на дроті немає. */
export const NO_CALL_GROUP: string = uk['callGate.noGroup'];
/** Без адреси сигнал дзвінка нема куди слати — і вигадувати її не можна. */
export const NO_CALL_NO_PEER: string = uk['callGate.noPeer'];

/** Чи несе вузол вхідні дзвінки. Телефон і ПК читають це поле по-різному. */
export function callsCarried(roads: CallRoads | null | undefined, phoneShell: boolean): boolean {
  return phoneShell ? roads?.inbound === true : roads?.inbound !== false;
}

/**
 * Один розрахунок на обидві слухавки.
 *
 * Порядок причин не довільний: коли доріг немає, адреса й рід розмови вже не
 * мають значення, і сказати треба про дороги. Інакше людина чинила б те, що
 * все одно не допоможе.
 */
export function callGate(opts: {
  roads: CallRoads | null | undefined;
  phoneShell: boolean;
  /** Є куди слати: вузлова адреса або контакт у ростері. */
  hasPeer: boolean;
  isGroup: boolean;
}): CallGate {
  if (!callsCarried(opts.roads, opts.phoneShell)) return { can: false, why: tNow('callGate.noRoads') };
  if (opts.isGroup && !opts.phoneShell) return { can: false, why: tNow('callGate.noGroup') };
  if (!opts.hasPeer) return { can: false, why: tNow('callGate.noPeer') };
  return { can: true, why: '' };
}
