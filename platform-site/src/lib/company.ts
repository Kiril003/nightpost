import { SELLING_ENTITY, entityIsConfigured } from './entity';

/**
 * Компанія: канали звернень і правовий стан.
 *
 * Досі весь сайт вів на одну адресу support@ — і кнопка «запросити демо», і
 * розкриття вразливості, і питання про рахунок. Одна скринька на все — це
 * найпомітніша ознака того, що по той бік одна людина. Канали розділено за
 * тим, ЩО в них пишуть, а не за тим, хто відповідає: відповідати може та сама
 * людина, але адресат у листа має бути правильний із першого разу.
 *
 * Реквізити юрособи навмисно НЕ живуть тут — вони в entity.ts, і поки їх
 * немає, сайт каже це прямо замість вигаданої назви.
 */

export const DOMAIN = 'phantom-os.dev';

export type ChannelId = 'sales' | 'support' | 'security' | 'press';

export interface Channel {
  id: ChannelId;
  email: string;
  /** Тема листа, підставлена наперед. */
  subject: string;
}

const at = (box: string) => `${box}@${DOMAIN}`;

export const CHANNELS: Record<ChannelId, Channel> = {
  sales: { id: 'sales', email: at('sales'), subject: 'Nightpost — демонстрація' },
  support: { id: 'support', email: at('support'), subject: 'Nightpost — підтримка' },
  security: { id: 'security', email: at('security'), subject: 'Nightpost — повідомлення про вразливість' },
  press: { id: 'press', email: at('press'), subject: 'Nightpost — медіа та партнерства' },
};

/** Лист у канал із наперед заповненою темою (і, за потреби, тілом). */
export function mailto(id: ChannelId, subject?: string, body?: string): string {
  const c = CHANNELS[id];
  const q = [`subject=${encodeURIComponent(subject ?? c.subject)}`];
  if (body) q.push(`body=${encodeURIComponent(body)}`);
  return `mailto:${c.email}?${q.join('&')}`;
}

/**
 * Правовий стан продажу.
 *
 * `pending` — суб'єкта господарювання ще не зареєстровано, тож продаж не
 * відкрито; сайт зобов'язаний казати це на видноті, а не ховати в /legal.
 * Щойно в entity.ts з'являться реквізити, стан сам стає `trading`, і жоден
 * рядок тут правити не треба.
 */
export type LegalStatus = 'pending' | 'trading';

export function legalStatus(): LegalStatus {
  return entityIsConfigured() ? 'trading' : 'pending';
}

/** Назва продавця для підвалу — або реквізит, або чесний прочерк. */
export function sellerLine(lang: 'uk' | 'en'): string {
  if (legalStatus() === 'trading' && SELLING_ENTITY.legalName) {
    const parts = [SELLING_ENTITY.legalName, SELLING_ENTITY.registrationId, SELLING_ENTITY.jurisdiction];
    return parts.filter(Boolean).join(' · ');
  }
  return lang === 'uk'
    ? 'Суб’єкта господарювання ще не зареєстровано — продаж не відкрито.'
    : 'No trading entity registered yet — sales are not open.';
}

/** Юрисдикція, у якій ведеться робота. Це факт, а не реквізит. */
export const JURISDICTION = { uk: 'Україна', en: 'Ukraine' } as const;
