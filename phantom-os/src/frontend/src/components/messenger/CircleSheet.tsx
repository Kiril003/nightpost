import React, { useEffect, useId, useState } from 'react';
import { Check, Plus } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import { useMessengerStore } from '../../stores/messengerStore';
import { useMoments } from '../../services/moments';
import { whereApi, wherePeerOf } from '../../services/peerWhere';
import { isWider, reachOf, type RingPeer, type RingRow } from '../../services/ringDowngrade';
import { ringMoveDiff, type RingMoveDiff } from '../../services/ringMoveDiff';
import { useCircleRights, withRights } from '../../stores/circleRightsStore';
import { moneyTies, type MoneyTie } from '../../services/money/ties';
import { fromUnits } from '../../services/meetup/split';
import { circleName, DEFAULT_CIRCLES, useCirclesStore } from '../../stores/circlesStore';
import type { ChatCircle } from '../../types/messenger';
import { canHaveCircle } from './circles';
import { RingDowngradeSheet } from './RingDowngradeSheet';
import { CIRCLES, isRealCircle } from './todayFormat';
import { DoorGroup, DoorRow, FormSheet, PrimaryButton } from './FormSheet';
import { pluralKey } from '../chat/tNow';

export interface CircleSheetProps {
  chatId: string;
  open: boolean;
  onClose: () => void;
  onDone?: () => void;
}

export const CircleSheet: React.FC<CircleSheetProps> = ({ chatId, open, onClose, onDone }) => {
  const { t, locale } = useTranslation();
  const labelId = useId();
  const chat = useMessengerStore((s) => s.chats.find((c) => c.id === chatId));
  const circles = useCirclesStore((s) => s.circles);
  const maxCustom = useCirclesStore((s) => s.maxCustom);
  const [step, setStep] = useState<'list' | 'create' | 'confirm'>('list');
  const [confirmMove, setConfirmMove] = useState<{ circleId: string; diff: RingMoveDiff; ties: MoneyTie[] } | null>(null);
  const [pending, setPending] = useState<Record<string, boolean>>({});
  const [name, setName] = useState('');
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<'tooMany' | 'failed' | null>(null);
  const [liveWhere, setLiveWhere] = useState(false);
  const [downgradeRows, setDowngradeRows] = useState<RingRow[] | null>(null);
  const wherePeerId = wherePeerOf(chat)?.id ?? null;
  const peer: RingPeer | undefined =
    chat && canHaveCircle(chat) && chat.type !== 'group' && chat.peerNodeId
      ? { id: chat.peerNodeId, chatId, name: chat.title }
      : undefined;

  useEffect(() => {
    if (open) {
      setStep('list');
      setConfirmMove(null);
      setDowngradeRows(null);
      setLiveWhere(false);
      setName('');
      setError(null);
      void useCirclesStore.getState().load();
    }
  }, [open]);

  useEffect(() => {
    if (!open || !wherePeerId) return;
    let alive = true;
    whereApi
      .get(wherePeerId)
      .then((w) => {
        if (alive) setLiveWhere(Boolean(w.mine && (w.mine.until_ms === null || w.mine.until_ms > w.now_ms)));
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [open, wherePeerId]);

  const activeIds: string[] = chat?.circles
    ? chat.circles
    : chat?.circle && isRealCircle(chat.circle)
      ? [chat.circle]
      : [];

  const rows = circles.length > 0 ? circles : DEFAULT_CIRCLES;
  const rightsOverrides = useCircleRights((s) => s.overrides);
  const customCount = rows.filter((c) => !c.builtin).length;
  const canAddCustom = customCount < maxCustom;

  const runToggle = async (circleId: string, nextOn: boolean) => {
    if (pending[circleId]) return;
    const closestBefore: ChatCircle = CIRCLES.find((c) => activeIds.includes(c)) ?? 'all';
    const after = nextOn ? [...activeIds, circleId] : activeIds.filter((id) => id !== circleId);
    const closestAfter: ChatCircle = CIRCLES.find((c) => after.includes(c)) ?? 'all';
    setPending((p) => ({ ...p, [circleId]: true }));
    const moved = await useCirclesStore.getState().toggle(chatId, circleId, nextOn);
    setPending((p) => {
      const next = { ...p };
      delete next[circleId];
      return next;
    });
    if (moved && !nextOn && isRealCircle(circleId as ChatCircle) && peer && isWider(closestBefore, closestAfter)) {
      const found = await reachOf(peer);
      if (found.length > 0) setDowngradeRows(found);
    }
  };

  const handleToggle = async (circleId: string) => {
    if (pending[circleId]) return;
    const isTicked = activeIds.includes(circleId);
    if (isTicked) {
      const after = activeIds.filter((id) => id !== circleId);
      const diff = ringMoveDiff({
        before: activeIds,
        after,
        circles: withRights(rows, rightsOverrides),
        liveWhere,
        momentsNaming: (ringId) =>
          (useMoments.getState().list ?? []).filter(
            (m) =>
              m.mine &&
              m.reach === 'circles' &&
              (m.keep || !m.untilMs || m.untilMs > Date.now()) &&
              Array.isArray(m.circles) &&
              m.circles.includes(ringId) &&
              !m.circles.some((c) => after.includes(c)),
          ).length,
      });
      const meId = useMessengerStore.getState().currentUser?.id;
      const ties = chat && meId ? moneyTies(chat.messages ?? [], meId) : [];
      if (diff.loses.length > 0 || ties.length > 0) {
        setConfirmMove({ circleId, diff, ties });
        setStep('confirm');
        return;
      }
    }
    await runToggle(circleId, !isTicked);
  };

  const handleCancelMove = () => {
    setConfirmMove(null);
    setStep('list');
  };

  const handleConfirmMove = async () => {
    if (!confirmMove) return;
    const { circleId } = confirmMove;
    await runToggle(circleId, false);
    setConfirmMove(null);
    setStep('list');
  };

  const handleCreate = async () => {
    const trimmed = name.trim();
    if (!trimmed || creating) return;
    setCreating(true);
    setError(null);
    const res = await useCirclesStore.getState().create(trimmed);
    if (res === 'ok') {
      const created = useCirclesStore
        .getState()
        .circles.filter((c) => !c.builtin && c.name === trimmed)
        .pop();
      if (created) {
        await useCirclesStore.getState().toggle(chatId, created.id, true);
      }
      setCreating(false);
      setName('');
      setStep('list');
    } else {
      setCreating(false);
      setError(res === 'tooMany' ? 'tooMany' : 'failed');
    }
  };

  const finish = () => {
    (onDone ?? onClose)();
  };

  if (step === 'confirm' && confirmMove) {
    return (
      <FormSheet
        open={open}
        onClose={onClose}
        onBack={handleCancelMove}
        title={t('ringMove.title')}
        testId="circle-multi-sheet"
        footer={
          <div className="flex flex-col gap-2">
            <PrimaryButton
              onClick={() => void handleConfirmMove()}
              disabled={Boolean(pending[confirmMove.circleId])}
            >
              {t('ringMove.move')}
            </PrimaryButton>
            <button
              type="button"
              onClick={handleCancelMove}
              className="w-full h-12 px-4 rounded-control bg-surface border border-line text-body font-medium text-ink transition-colors duration-fast"
            >
              {t('ringMove.cancel')}
            </button>
          </div>
        }
      >
        <DoorGroup inset="text" className="mt-2 mb-4">
          {confirmMove.diff.loses.map((item) => (
            <div key={item} className="min-h-12 px-4 py-1.5 flex items-center text-subhead text-ink">
              {item === 'location'
                ? t('ringMove.location')
                : t(pluralKey(locale, confirmMove.diff.momentsLost, 'ringMove.moments'), { count: confirmMove.diff.momentsLost })}
            </div>
          ))}
          {confirmMove.ties.map((tie) => (
            <div
              key={`${tie.dir}:${tie.token}`}
              data-testid="ring-move-tie"
              className="min-h-12 px-4 py-1.5 flex items-center text-subhead text-ink"
            >
              {t(tie.dir === 'iOwe' ? 'ringMove.iOwe' : 'ringMove.owesMe', {
                amount: `${fromUnits(tie.units, tie.token)} ${tie.token}`,
              })}
            </div>
          ))}
        </DoorGroup>
      </FormSheet>
    );
  }

  if (step === 'create') {
    return (
      <FormSheet
        open={open}
        onClose={onClose}
        onBack={() => {
          setStep('list');
          setError(null);
        }}
        title={t('circles.new')}
        testId="circle-multi-sheet"
        footer={
          <PrimaryButton onClick={() => void handleCreate()} disabled={!name.trim() || creating}>
            {t('circles.create')}
          </PrimaryButton>
        }
      >
        <div className="px-4 pt-4 pb-2 flex flex-col gap-2">
          <label htmlFor={labelId} className="text-footnote text-ink-2">
            {t('circles.name')}
          </label>
          <input
            id={labelId}
            type="text"
            maxLength={32}
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="w-full h-12 px-3 rounded-control bg-surface text-body text-ink border border-line focus:outline-none focus:border-accent-line"
          />
          {error && (
            <p className="text-footnote text-danger">
              {error === 'tooMany' ? t('circles.tooMany') : t('circles.saveFailed')}
            </p>
          )}
        </div>
      </FormSheet>
    );
  }

  return (
    <>
      <FormSheet
        open={open}
        onClose={onClose}
        title={t('circles.who')}
        testId="circle-multi-sheet"
        footer={<PrimaryButton onClick={finish}>{t('circles.done')}</PrimaryButton>}
      >
        <div className="pb-2">
          {rows.map((c) => {
            const ticked = activeIds.includes(c.id);
            return (
              <DoorRow
                key={c.id}
                label={circleName(c, t)}
                onClick={() => void handleToggle(c.id)}
                trailing={
                  ticked ? (
                    <Check
                      className="w-5 h-5 shrink-0 text-ink"
                      strokeWidth={1.75}
                      aria-label={t('phoneChatMenu.selected')}
                    />
                  ) : undefined
                }
              />
            );
          })}
          {canAddCustom && (
            <DoorRow
              icon={<Plus className="w-5 h-5" strokeWidth={1.75} />}
              label={t('circles.new')}
              onClick={() => {
                setName('');
                setError(null);
                setStep('create');
              }}
            />
          )}
        </div>
      </FormSheet>
      {peer && downgradeRows && downgradeRows.length > 0 && (
        <RingDowngradeSheet
          peer={peer}
          rows={downgradeRows}
          open
          onClose={() => setDowngradeRows(null)}
        />
      )}
    </>
  );
};
