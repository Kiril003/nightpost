import React from 'react';
import { useTranslation } from '../../../i18n/useTranslation';

/** Гроші тестової мережі: тихий нейтральний знак у шапці картки, не другий акцент. */
export const TestPayTag: React.FC = () => {
  const { t } = useTranslation();
  return (
    <span data-testid="test-pay-tag" className="ml-auto shrink-0 px-2 py-0.5 rounded-full bg-overlay text-caption font-medium text-ink-2">
      {t('coreChat.testPay')}
    </span>
  );
};
