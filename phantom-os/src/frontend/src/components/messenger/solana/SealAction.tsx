/**
 * «Засвідчити» фото чи файл: веб рахує SHA-256 розшифрованого файла
 * (WebCrypto) і віддає телефону ЛИШЕ відбиток. Підпис — на рідному екрані
 * гаманця; картку печатки в розмову шле сам телефон (`peer_id`).
 */
import React, { useState } from 'react';
import { FormSheet, PrimaryButton } from '../FormSheet';
import { sealAskFor, solanaApi } from '../../../services/solanaActions';
import type { Message } from '../../../types/messenger';

import { useTranslation } from '../../../i18n/useTranslation';

export const SealSheet: React.FC<{
  msg: Message;
  peer: { id: string; name: string };
  onClose: () => void;
}> = ({ msg, peer, onClose }) => {
  const { t } = useTranslation();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const go = () => {
    setBusy(true);
    setError(null);
    sealAskFor(msg, peer.id)
      .then((ask) => solanaApi.seal(ask))
      .then(
        () => onClose(),
        (e: { message?: string }) => {
          setBusy(false);
          setError(e?.message === 'no_file' ? t('sealAction.noFile') : e?.message || t('sealAction.failed'));
        },
      );
  };

  return (
    <FormSheet
      open
      onClose={onClose}
      title={t('sealAction.seal')}
      testId="solana-seal-sheet"
      footer={
        <PrimaryButton disabled={busy} onClick={go}>
          {t('sealAction.seal')}
        </PrimaryButton>
      }
    >
      <div className="px-4 pb-2 space-y-2">
        {msg.media?.name && <p className="text-body text-ink truncate">{msg.media.name}</p>}
        <p className="text-callout text-ink-2">{t('sealAction.sentence')}</p>
        {error && <p className="text-callout text-danger">{error}</p>}
      </div>
    </FormSheet>
  );
};

export default SealSheet;
