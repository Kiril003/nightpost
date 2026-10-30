/**
 * Один господар аркушів Solana на всю розмову. Меню, з якого людина прийшла
 * (аркуш вкладень, аркуш дій над листом), закривається першим — тож аркуш
 * не може жити в ньому.
 */
import React from 'react';
import { askSolana, useSolanaAsk } from '../../../services/solanaActions';
import { GroupMoneySheet } from './GroupMoneySheet';
import { PayMoneySheet } from './PayMoneySheet';
import { PayNoteWatcher } from './PayNoteWatcher';
import { SealSheet } from './SealAction';

export const SolanaSheets: React.FC = () => {
  const ask = useSolanaAsk();
  const close = () => askSolana(null);
  const sheet = !ask ? null : ask.kind === 'group' ? (
    <GroupMoneySheet chatId={ask.chatId} file={ask.file} peer={ask.peer} onClose={close} />
  ) : ask.kind === 'pay' ? (
    <PayMoneySheet peer={ask.peer} cluster={ask.cluster} chat={ask.chat} onClose={close} />
  ) : (
    <SealSheet msg={ask.msg} peer={ask.peer} onClose={close} />
  );
  return (
    <>
      <PayNoteWatcher />
      {sheet}
    </>
  );
};

export default SolanaSheets;
