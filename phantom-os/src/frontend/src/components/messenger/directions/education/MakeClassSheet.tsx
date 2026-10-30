import React, { useMemo, useState } from 'react';
import { Users } from 'lucide-react';
import { useTranslation } from '../../../../i18n/useTranslation';
import { useMessengerStore } from '../../../../stores/messengerStore';
import { isClassChat } from '../../../../services/edu/eduModel';
import { makeClass } from '../../../../services/edu/eduActions';
import { displayTitle } from '../../chatListFormat';
import { DoorGroup, DoorRow, FormSheet } from '../../FormSheet';
import { EduPrimary, fieldClass } from './EduFrame';

/** Зробити групу класом: вибір групи (якщо не задана), назва, предмет. */
export const MakeClassSheet: React.FC<{ chatId?: string; onClose: () => void; onDone?: (chatId: string) => void }> = ({
  chatId,
  onClose,
  onDone,
}) => {
  const { t } = useTranslation();
  const chats = useMessengerStore((s) => s.chats);
  const meId = useMessengerStore((s) => s.currentUser.id);
  const [picked, setPicked] = useState<string | null>(chatId ?? null);
  const groups = useMemo(() => chats.filter((c) => c.type === 'group' && !isClassChat(c, meId)), [chats, meId]);
  const chat = picked ? chats.find((c) => c.id === picked) : undefined;
  const [name, setName] = useState('');
  const [subject, setSubject] = useState('');
  const nameValue = name || (chat ? displayTitle(chat.title) : '');
  const ready = Boolean(picked && nameValue.trim());

  const create = () => {
    if (!picked || !ready) return;
    makeClass(picked, nameValue, subject);
    onDone?.(picked);
    onClose();
  };

  return (
    <FormSheet
      open
      onClose={onClose}
      title={picked ? t('edu.make') : t('edu.classes.pick')}
      testId="edu-make-class"
      onBack={picked && !chatId ? () => setPicked(null) : undefined}
      footer={
        picked ? (
          <EduPrimary testId="edu-make-create" disabled={!ready} onClick={create}>
            {t('edu.make.create')}
          </EduPrimary>
        ) : undefined
      }
    >
      {picked ? (
        <div className="px-4 pb-3 space-y-3">
          <input
            data-testid="edu-make-name"
            aria-label={t('edu.make.name')}
            placeholder={t('edu.make.name')}
            value={nameValue}
            maxLength={120}
            onChange={(e) => setName(e.target.value)}
            className={fieldClass}
          />
          <input
            data-testid="edu-make-subject"
            aria-label={t('edu.make.subject')}
            placeholder={t('edu.make.subject')}
            value={subject}
            maxLength={120}
            onChange={(e) => setSubject(e.target.value)}
            className={fieldClass}
          />
        </div>
      ) : groups.length === 0 ? (
        <p data-testid="edu-make-nogroups" className="px-5 py-4 text-callout text-ink-2">
          {t('edu.classes.noGroups')}
        </p>
      ) : (
        <DoorGroup>
          {groups.map((g) => (
            <DoorRow
              key={g.id}
              icon={<Users className="w-5 h-5" strokeWidth={1.75} />}
              label={displayTitle(g.title)}
              onClick={() => setPicked(g.id)}
            />
          ))}
        </DoorGroup>
      )}
    </FormSheet>
  );
};
