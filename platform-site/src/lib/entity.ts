/**
 * Реквізити продавця.
 *
 * `null` означає «ще не вирішено», і сторінка правових умов каже це прямо.
 * Вигадати назву юрособи, ЄДРПОУ чи адресу тут не можна за жодних обставин:
 * документ із фальшивими реквізитами гірший за документ, який чесно каже
 * «продаж ще не відкрито».
 *
 * Коли суб'єкт господарювання зареєстровано — заповнюєш поля, і сторінка сама
 * перестає показувати попередження про чернетку.
 */
export interface SellingEntity {
  legalName: string | null;
  registrationId: string | null;
  address: string | null;
  jurisdiction: string | null;
  /** Як показуємо ПДВ/податки покупцеві. */
  vatNote: string | null;
}

export const SELLING_ENTITY: SellingEntity = {
  legalName: null,
  registrationId: null,
  address: null,
  jurisdiction: null,
  vatNote: null,
};

const REQUIRED: (keyof SellingEntity)[] = [
  'legalName',
  'registrationId',
  'address',
  'jurisdiction',
];

export function entityIsConfigured(entity: SellingEntity = SELLING_ENTITY): boolean {
  return REQUIRED.every((field) => {
    const value = entity[field];
    return typeof value === 'string' && value.trim().length > 0;
  });
}

/** Дата, з якої чинна ця редакція. Оновлюється разом із текстом. */
export const LEGAL_REVISION = '2026-10-01';
