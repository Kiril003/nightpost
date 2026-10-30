import type { Lang } from './types';

export interface DeleteAccountCopy {
  title: string;
  privacyTitle: string;
  confirmOnPhone: string;
  emailAccount: string;
  noPhone: string;
  removedAfter7Days: string;
  onlyOnPhone: string;
  notAvailable: string;
  accountDisabled: string;
  stepsTitle: string;
  steps: string[];
  deletedTitle: string;
  deleted: string[];
  keptTitle: string;
  kept: string[];
  send: string;
  sent: string;
  retry: string;
  message: string;
  deletionScheduled: string;
  cancelDeletion: string;
  accountRestored: string;
  toAccount: string;
  nothingToCancel: string;
}

export const deleteAccountCopy: Record<Lang, DeleteAccountCopy> = {
  en: {
    title: 'Delete account',
    privacyTitle: 'Privacy',
    confirmOnPhone: 'Confirm on your phone',
    emailAccount: 'Email account',
    noPhone: 'No phone',
    removedAfter7Days: 'Removed after 7 days',
    onlyOnPhone: 'Only from the app: place ratings, Seeker rewards',
    notAvailable: 'Not available for this account',
    accountDisabled: 'Account disabled',
    stepsTitle: 'Steps',
    steps: ['Phone or email', '7 days to cancel', 'Deleted'],
    deletedTitle: 'Deleted',
    deleted: [
      'Account, sign-ins and devices',
      'Name in the directory',
      'Support messages',
      'Your places and their ratings',
      'Map reports',
      'Licence activations',
    ],
    keptTitle: 'Kept',
    kept: [
      'Purchase record, without email or account',
      'Points history and linked wallet',
      'Message envelopes until collected, 7 days at most',
      'Solana payments: public on-chain, cannot be removed',
    ],
    send: 'Send',
    sent: 'Sent',
    retry: 'Try again',
    message: 'Message',
    deletionScheduled: 'Deletion scheduled',
    cancelDeletion: 'Cancel deletion',
    accountRestored: 'Account restored',
    toAccount: 'Open account',
    nothingToCancel: 'Nothing to cancel',
  },
  uk: {
    title: 'Видалити обліковий запис',
    privacyTitle: 'Приватність',
    confirmOnPhone: 'Підтвердіть на телефоні',
    emailAccount: 'Обліковий запис з поштою',
    noPhone: 'Немає телефона',
    removedAfter7Days: 'Видаляється через 7 днів',
    onlyOnPhone: 'Лише із застосунку: оцінки місць, нагороди Seeker',
    notAvailable: 'Недоступно для цього облікового запису',
    accountDisabled: 'Обліковий запис вимкнено',
    stepsTitle: 'Кроки',
    steps: ['Телефон або пошта', '7 днів, щоб скасувати', 'Видалено'],
    deletedTitle: 'Видаляється',
    deleted: [
      'Обліковий запис, входи й пристрої',
      'Імʼя в довіднику',
      'Листування з підтримкою',
      'Ваші місця та їхні оцінки',
      'Свідчення на мапі',
      'Активації ліцензії',
    ],
    keptTitle: 'Лишається',
    kept: [
      'Запис покупки без пошти й облікового запису',
      'Історія балів і привʼязаний гаманець',
      'Конверти листів до забору, щонайбільше 7 днів',
      'Платежі Solana: публічні в мережі, видалити не можна',
    ],
    send: 'Надіслати',
    sent: 'Надіслано',
    retry: 'Спробуйте ще раз',
    message: 'Повідомлення',
    deletionScheduled: 'Видалення заплановано',
    cancelDeletion: 'Скасувати видалення',
    accountRestored: 'Обліковий запис відновлено',
    toAccount: 'До кабінету',
    nothingToCancel: 'Нічого скасовувати',
  },
};
