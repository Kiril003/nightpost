/** Правка аудиторії свого моменту: той самий листок «Хто побачить», що й у новому моменті. */
import React, { useMemo, useState } from 'react';
import { useTranslation } from '../../i18n/useTranslation';
import { useMessengerStore } from '../../stores/messengerStore';
import { audienceCount, momentCircles } from '../../services/audienceCount';
import { useOwnedPeers } from '../../services/momentProfile';
import { useMoments, type Audience, type Moment, type Reach } from '../../services/moments';
import { AUDIENCE_RUNGS, MomentAudienceSheet } from './MomentAudienceSheet';
import { momentPeople } from './MomentViewer';

export interface MomentAudienceEditProps {
  m: Moment;
  onClose: () => void;
  onFailed: (code: string) => void;
}

export const MomentAudienceEdit: React.FC<MomentAudienceEditProps> = ({ m, onClose, onFailed }) => {
  const { t } = useTranslation();
  const allChats = useMessengerStore((s) => s.chats);
  const { owned, scoped } = useOwnedPeers(allChats);
  const chats = useMemo(() => allChats.filter((c) => !c.peerNodeId || owned(c.peerNodeId)), [allChats, owned]);
  const people = useMemo(() => momentPeople(chats), [chats]);
  const circles = useMemo(() => momentCircles(chats), [chats]);
  const everyone = useMoments((s) => s.prefs?.everyone === true);
  const reaudience = useMoments((s) => s.reaudience);
  const [reach, setReach] = useState<Reach>(m.reach === 'everyone' && !everyone ? 'contacts' : m.reach);
  const [names, setNames] = useState<string[]>(m.reach === 'circles' ? m.circles : []);
  const [ids, setIds] = useState<string[]>(m.reach === 'people' ? (m.to ?? []) : []);
  const [busy, setBusy] = useState(false);

  const rungs = AUDIENCE_RUNGS.filter((r) => (r.reach === 'everyone' ? everyone : r.reach !== 'circles' || circles.length > 0));
  const chosen = useMemo(() => circles.filter((c) => names.includes(c.name)), [circles, names]);
  const circleIds = useMemo(() => [...new Set(chosen.flatMap((c) => c.ids))], [chosen]);
  const peopleIds = useMemo(() => ids.filter((id) => people.some((p) => p.id === id)), [ids, people]);
  const audience: Audience =
    reach === 'me' ? { kind: 'me' }
      : reach === 'people' ? { kind: 'people', ids: peopleIds }
        : reach === 'circles' ? { kind: 'circles', names: chosen.map((c) => c.name), ids: circleIds }
          : reach === 'everyone' ? { kind: 'everyone' }
            : { kind: 'contacts' };
  const count = useMemo(() => audienceCount(audience, chats), [audience, chats]);
  const reachable =
    reach === 'me' || reach === 'everyone'
    || (people.length > 0 && (reach === 'people' ? peopleIds.length > 0 : reach === 'circles' ? circleIds.length > 0 : true));

  const save = async () => {
    if (busy || !reachable) return;
    setBusy(true);
    try {
      await reaudience(m.id, audience);
      onClose();
    } catch (e) {
      onClose();
      onFailed((e as { code?: string } | null)?.code || '');
    }
  };

  return (
    <MomentAudienceSheet
      rungs={rungs}
      reach={reach}
      onReach={setReach}
      circles={circles}
      chosenCircles={chosen.map((c) => c.name)}
      onToggleCircle={(name) => setNames((cur) => (cur.includes(name) ? cur.filter((x) => x !== name) : [...cur, name]))}
      people={people}
      activePeopleIds={peopleIds}
      onTogglePerson={(id, on) => setIds((cur) => (on ? cur.filter((x) => x !== id) : [...cur, id]))}
      count={count}
      seeN={scoped}
      ready
      noOne={people.length === 0}
      outerRings={[]}
      onLeaveOut={() => undefined}
      onDone={() => void save()}
      onCancel={onClose}
      doneLabel={t('moments.save')}
      doneDisabled={busy || !reachable}
    />
  );
};
