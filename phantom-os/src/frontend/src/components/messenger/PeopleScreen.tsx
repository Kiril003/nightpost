import React, { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Ban, BellOff, Check, ChevronRight, Eye, Inbox, Shield, Users, UserMinus } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import { pluralKey } from '../chat/tNow';
import { useMessengerStore } from '../../stores/messengerStore';
import { circleName, DEFAULT_CIRCLES, useCirclesStore } from '../../stores/circlesStore';
import { lostCaps, personRights, useCircleRights } from '../../stores/circleRightsStore';
import { registerPhoneLayer } from '../../stores/phoneLayerStore';
import { useEscapeClose } from '../../hooks/useEscapeClose';
import { useEdgeStretch } from '../../feel/useEdgeStretch';
import { policy, readEnv } from '../../feel/motionTokens';
import { messengerApi } from '../../services/messengerApi';
import { chatHush } from '../../services/chatHush';
import { useRequestsStore } from '../../stores/requestsStore';
import type { StagedKind } from '../../stores/requestsStore';
import { soundFx } from '../../utils/messengerSound';
import type { Chat } from '../../types/messenger';
import { Avatar } from './Avatar';
import { PeerProfileSheet } from './PeerProfileSheet';
import { personFacts, personName } from './personFacts';
import { canHaveCircle } from './circles';
import { displayTitle } from './chatListFormat';
import { DoorGroup, DoorRow, FormSheet } from './FormSheet';
import { EmptyState } from './kit/EmptyState';
import { TopBar } from './kit/TopBar';
import { Toast } from './Toast';
import { CircleRightsView, MoveConfirmBody, SeeAsView } from './PeopleRights';

export interface BlockedPeer {
  id: string;
  name: string;
}

export const useBlockedPeers = () => {
  const [blocked, setBlocked] = useState<BlockedPeer[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  const reload = useCallback(() => {
    setLoading(true);
    setError(false);
    void Promise.all([messengerApi.listBlocked(), messengerApi.listContacts().catch(() => [])])
      .then(([b, contacts]) => {
        const nameOf = new Map(contacts.map((c) => [c.peer_node_id, c.display_name]));
        setBlocked(b.blocked.map((id) => ({ id, name: nameOf.get(id) || '' })));
        setLoading(false);
      })
      .catch(() => {
        setBlocked(null);
        setError(true);
        setLoading(false);
      });
  }, []);

  useEffect(() => {
    reload();
  }, [reload]);

  const unblock = useCallback(async (id: string): Promise<boolean> => {
    soundFx.playTap();
    try {
      const r = await messengerApi.unblockPeer(id);
      if (!r?.blocked) {
        setBlocked((list) => (list ?? []).filter((b) => b.id !== id));
        return true;
      }
      return false;
    } catch {
      return false;
    }
  }, []);

  const reblock = useCallback(async (peer: BlockedPeer): Promise<boolean> => {
    soundFx.playTap();
    try {
      const r = await messengerApi.blockPeer(peer.id);
      if (r?.blocked !== false) {
        setBlocked((list) => {
          const cur = list ?? [];
          return cur.some((b) => b.id === peer.id) ? cur : [...cur, peer];
        });
        return true;
      }
      return false;
    } catch {
      return false;
    }
  }, []);

  return { blocked, setBlocked, loading, error, reload, unblock, reblock };
};

type PeopleView =
  | { kind: 'home' }
  | { kind: 'circle'; circleId: string }
  | { kind: 'rights'; circleId: string }
  | { kind: 'seeas'; circleId: string }
  | { kind: 'requests' }
  | { kind: 'blocked' };

type ActionStep = 'menu' | 'move' | 'confirm' | 'mute' | 'block';

const ICON = { className: 'w-5 h-5', strokeWidth: 1.75 } as const;

interface PersonActionsSheetProps {
  chat: Chat | null;
  circleId: string;
  onClose: () => void;
  onRemoveFromCircle: (chat: Chat, circleId: string) => Promise<void>;
  onBlockPeer: (chat: Chat) => Promise<void>;
}

const PersonActionsSheet: React.FC<PersonActionsSheetProps> = ({
  chat,
  circleId,
  onClose,
  onRemoveFromCircle,
  onBlockPeer,
}) => {
  const { t } = useTranslation();
  const circles = useCirclesStore((s) => s.circles);
  const [step, setStep] = useState<ActionStep>('menu');
  const [busy, setBusy] = useState(false);
  const [target, setTarget] = useState<string | null>(null);
  const overrides = useCircleRights((s) => s.overrides);

  useEffect(() => {
    setStep('menu');
    setTarget(null);
    setBusy(false);
  }, [chat?.id, circleId]);

  if (!chat) return null;

  const name = displayTitle(chat.title);
  const rows = circles.length > 0 ? circles : DEFAULT_CIRCLES;
  const muted = chatHush.has(chat.id);

  const targetCircle = rows.find((c) => c.id === target);
  const currentCircle = rows.find((c) => c.id === circleId);
  const lost = (() => {
    if (!target) return [];
    const before = chat.circles?.length ? chat.circles : chat.circle ? [chat.circle] : [circleId];
    const after = [...before.filter((id) => id !== circleId), target];
    return lostCaps(personRights(before, rows, overrides), personRights(after, rows, overrides));
  })();

  const sheetTitle =
    step === 'confirm' && currentCircle && targetCircle
      ? t('people.moveArrow', { from: circleName(currentCircle, t), to: circleName(targetCircle, t) })
      : step === 'move'
      ? t('people.moveToCircle')
      : step === 'mute'
        ? t('people.mute')
        : step === 'block'
          ? t('people.block')
          : name;

  const pickTarget = (targetId: string) => {
    if (busy || targetId === circleId) return;
    const before = chat.circles?.length ? chat.circles : chat.circle ? [chat.circle] : [circleId];
    const after = [...before.filter((id) => id !== circleId), targetId];
    const l = lostCaps(personRights(before, rows, overrides), personRights(after, rows, overrides));
    if (l.length === 0) {
      void handleMove(targetId);
      return;
    }
    setTarget(targetId);
    setStep('confirm');
  };

  const handleMove = async (targetId: string) => {
    if (busy || targetId === circleId) return;
    setBusy(true);
    try {
      const joined = await useCirclesStore.getState().toggle(chat.id, targetId, true);
      if (joined) {
        await useCirclesStore.getState().toggle(chat.id, circleId, false);
      }
      onClose();
    } finally {
      setBusy(false);
    }
  };

  return (
    <FormSheet
      open
      onClose={onClose}
      onBack={step === 'menu' ? undefined : () => setStep('menu')}
      title={sheetTitle}
      testId="person-actions-sheet"
      footer={
        step === 'confirm' ? (
          <div className="flex flex-col gap-2">
            <button
              type="button"
              data-testid="move-confirm-btn"
              disabled={busy || !target}
              onClick={() => target && void handleMove(target)}
              className="w-full h-12 min-h-0 rounded-control bg-accent text-headline text-on-accent disabled:opacity-40"
            >
              {t('people.moveDo')}
            </button>
            <button
              type="button"
              onClick={() => setStep('move')}
              className="w-full h-12 min-h-0 rounded-control text-headline text-ink-2 active:bg-raised"
            >
              {t('people.cancel')}
            </button>
          </div>
        ) : step === 'block' ? (
          <div className="flex flex-col gap-2">
            <button
              type="button"
              data-testid="block-confirm-btn"
              disabled={busy}
              onClick={async () => {
                if (busy) return;
                setBusy(true);
                try {
                  await onBlockPeer(chat);
                  onClose();
                } finally {
                  setBusy(false);
                }
              }}
              className="w-full h-12 min-h-0 rounded-control bg-raised text-headline text-danger active:opacity-90"
            >
              {t('people.block')}
            </button>
            <button
              type="button"
              onClick={() => setStep('menu')}
              className="w-full h-12 min-h-0 rounded-control text-headline text-ink-2 active:bg-raised"
            >
              {t('people.cancel')}
            </button>
          </div>
        ) : undefined
      }
    >
      {step === 'menu' && (
        <div className="pb-2 flex flex-col gap-3">
          <DoorGroup>
            <DoorRow
              icon={<Users {...ICON} />}
              label={t('people.moveToCircle')}
              onClick={() => setStep('move')}
              trailing={<ChevronRight className="w-4 h-4 text-ink-3" strokeWidth={1.75} aria-hidden />}
            />
            <DoorRow
              icon={<UserMinus {...ICON} />}
              label={t('people.removeFromCircle')}
              onClick={() => void onRemoveFromCircle(chat, circleId)}
            />
            <DoorRow
              icon={<BellOff {...ICON} />}
              label={t('people.mute')}
              onClick={() => setStep('mute')}
              trailing={<ChevronRight className="w-4 h-4 text-ink-3" strokeWidth={1.75} aria-hidden />}
            />
          </DoorGroup>
          <DoorGroup>
            <DoorRow
              icon={<Ban {...ICON} className="w-5 h-5 text-danger" />}
              label={t('people.block')}
              tone="danger"
              onClick={() => setStep('block')}
            />
          </DoorGroup>
        </div>
      )}

      {step === 'move' && (
        <div className="pb-2">
          <DoorGroup inset="text">
            {rows.map((c) => {
              const isCurrent = c.id === circleId;
              return (
                <DoorRow
                  key={c.id}
                  label={circleName(c, t)}
                  onClick={() => pickTarget(c.id)}
                  trailing={
                    isCurrent ? (
                      <Check className="w-5 h-5 shrink-0 text-ink" strokeWidth={1.75} aria-hidden />
                    ) : undefined
                  }
                />
              );
            })}
          </DoorGroup>
        </div>
      )}

      {step === 'confirm' && <MoveConfirmBody name={name} lost={lost} />}

      {step === 'mute' && (
        <div className="pb-2">
          <DoorGroup inset="text">
            <DoorRow
              label={t('people.mute1h')}
              onClick={() => {
                chatHush.setFor(chat.id, Date.now() + 3_600_000);
                onClose();
              }}
            />
            <DoorRow
              label={t('people.mute8h')}
              onClick={() => {
                chatHush.setFor(chat.id, Date.now() + 8 * 3_600_000);
                onClose();
              }}
            />
            <DoorRow
              label={t('people.mute1d')}
              onClick={() => {
                chatHush.setFor(chat.id, Date.now() + 86_400_000);
                onClose();
              }}
            />
            <DoorRow
              label={t('people.muteForever')}
              onClick={() => {
                chatHush.setFor(chat.id, null);
                onClose();
              }}
            />
            {muted && (
              <DoorRow
                label={t('people.unmute')}
                onClick={() => {
                  chatHush.set(chat.id, false);
                  onClose();
                }}
              />
            )}
          </DoorGroup>
        </div>
      )}

      {step === 'block' && (
        <div className="pb-2">
          <DoorGroup inset="text">
            <div className="min-h-12 px-4 py-1.5 flex items-center justify-between gap-4">
              <span className="text-subhead text-ink">{t('people.blockMessagesCalls')}</span>
              <span className="text-subhead text-ink-2">{t('people.blockMessagesCallsVal')}</span>
            </div>
            <div className="min-h-12 px-4 py-1.5 flex items-center justify-between gap-4">
              <span className="text-subhead text-ink">{t('people.blockProfileMoments')}</span>
              <span className="text-subhead text-ink-2">{t('people.blockProfileMomentsVal')}</span>
            </div>
          </DoorGroup>
        </div>
      )}
    </FormSheet>
  );
};

export interface PeopleScreenProps {
  onClose: () => void;
  loading?: boolean;
  error?: boolean;
}

export const PeopleScreen: React.FC<PeopleScreenProps> = ({
  onClose,
  loading: loadingProp = false,
  error: errorProp = false,
}) => {
  const { t, locale } = useTranslation();
  const layerId = useId();
  const stretchRef = useRef<HTMLDivElement>(null);
  useEdgeStretch(stretchRef);

  const chats = useMessengerStore((s) => s.chats);
  const storeCircles = useCirclesStore((s) => s.circles);
  const circles = storeCircles.length > 0 ? storeCircles : DEFAULT_CIRCLES;

  const [view, setView] = useState<PeopleView>({ kind: 'home' });
  const [circlesLoading, setCirclesLoading] = useState(loadingProp);
  const [circlesError, setCirclesError] = useState(errorProp);
  const [activeMember, setActiveMember] = useState<{ chat: Chat; circleId: string } | null>(null);
  const [requestPerson, setRequestPerson] = useState<{ peer: string; name: string; avatar?: string; chatId?: string } | null>(null);
  const [snack, setSnack] = useState<{ message: string; action?: { label: string; run: () => void } } | null>(null);
  const [foldedIds, setFoldedIds] = useState<string[]>([]);
  const [restoredIds, setRestoredIds] = useState<string[]>([]);

  const {
    blocked,
    setBlocked,
    loading: blockedLoading,
    error: blockedError,
    reload: reloadBlocked,
    unblock,
    reblock,
  } = useBlockedPeers();

  const incoming = useRequestsStore((s) => s.incoming);
  const outgoing = useRequestsStore((s) => s.outgoing);
  const requestsLoaded = useRequestsStore((s) => s.loaded);
  const requestsError = useRequestsStore((s) => s.error);
  const [choosingId, setChoosingId] = useState<string | null>(null);

  useEffect(() => {
    void useRequestsStore.getState().load();
    return () => useRequestsStore.getState().flush();
  }, []);

  const motionPolicy = useMemo(() => policy(readEnv()), []);
  const foldSpec = motionPolicy.fold;
  const settleSpec = motionPolicy.settle;

  const loadCircles = useCallback(() => {
    setCirclesError(false);
    void useCirclesStore
      .getState()
      .load()
      .catch(() => {
        setCirclesError(true);
      });
  }, []);

  useEffect(() => {
    loadCircles();
  }, [loadCircles]);

  const stepBack = useCallback(() => {
    if (view.kind === 'rights') setView({ kind: 'circle', circleId: view.circleId });
    else if (view.kind !== 'home') setView({ kind: 'home' });
    else onClose();
  }, [view.kind, onClose]);

  const stepBackRef = useRef(stepBack);
  stepBackRef.current = stepBack;
  useEffect(() => registerPhoneLayer(`people${layerId}`, true, () => stepBackRef.current()), [layerId]);
  useEscapeClose(activeMember === null, () => stepBackRef.current());

  const membersOf = useCallback(
    (circleId: string): Chat[] =>
      chats.filter((c) => canHaveCircle(c) && (c.circles?.includes(circleId) || c.circle === circleId)),
    [chats],
  );

  const handleRemoveFromCircle = async (chat: Chat, circleId: string) => {
    const name = displayTitle(chat.title);
    setActiveMember(null);
    const removed = await useCirclesStore.getState().toggle(chat.id, circleId, false);
    if (!removed) return;
    if (!foldSpec.end) {
      setFoldedIds((prev) => (prev.includes(chat.id) ? prev : [...prev, chat.id]));
    }
    setSnack({
      message: t('people.removedSnack', { name }),
      action: {
        label: t('people.undo'),
        run: () => {
          void useCirclesStore
            .getState()
            .toggle(chat.id, circleId, true)
            .then((ok) => {
              if (ok) {
                setFoldedIds((prev) => prev.filter((id) => id !== chat.id));
                setRestoredIds((prev) => (prev.includes(chat.id) ? prev : [...prev, chat.id]));
              }
            });
        },
      },
    });
  };

  const handleBlockPeer = async (chat: Chat) => {
    const peerId = chat.peerNodeId || chat.id;
    const name = displayTitle(chat.title);
    await messengerApi.blockPeer(peerId);
    setBlocked((list) => {
      const cur = list ?? [];
      return cur.some((b) => b.id === peerId) ? cur : [...cur, { id: peerId, name }];
    });
  };

  const handleUnblockPeer = async (peer: BlockedPeer) => {
    const displayName = personName(peer.name, t);
    const ok = await unblock(peer.id);
    if (!ok) return;
    if (!foldSpec.end) {
      setFoldedIds((prev) => (prev.includes(peer.id) ? prev : [...prev, peer.id]));
    }
    setSnack({
      message: t('people.unblockedSnack', { name: displayName }),
      action: {
        label: t('people.undo'),
        run: () => {
          void reblock(peer).then((restored) => {
            if (restored) {
              setFoldedIds((prev) => prev.filter((id) => id !== peer.id));
              setRestoredIds((prev) => (prev.includes(peer.id) ? prev : [...prev, peer.id]));
            }
          });
        },
      },
    });
  };

  const stageRequest = (id: string, kind: StagedKind, name: string, circle?: string) => {
    soundFx.playTap();
    setChoosingId(null);
    useRequestsStore.getState().stage(id, kind, circle);
    if (!foldSpec.end) setFoldedIds((prev) => (prev.includes(id) ? prev : [...prev, id]));
    const key =
      kind === 'accept' ? 'people.requestAcceptedSnack' : kind === 'decline' ? 'people.requestDeclinedSnack' : 'people.requestWithdrawnSnack';
    setSnack({
      message: t(key, { name }),
      action: {
        label: t('people.undo'),
        run: () => {
          if (useRequestsStore.getState().undo(id)) {
            setFoldedIds((prev) => prev.filter((x) => x !== id));
            setRestoredIds((prev) => (prev.includes(id) ? prev : [...prev, id]));
          }
        },
      },
    });
  };

  const activeCircle =
    view.kind === 'circle' || view.kind === 'rights'
      ? circles.find((c) => c.id === view.circleId) ?? { id: view.circleId, name: null }
      : null;
  const circleMembers = view.kind === 'circle' ? membersOf(view.circleId) : [];

  const topTitle =
    view.kind === 'seeas'
      ? t('people.seeAs')
      : view.kind === 'rights'
        ? t('people.rights')
        : view.kind === 'circle' && activeCircle
      ? circleName(activeCircle, t)
      : view.kind === 'requests'
        ? t('people.requests')
        : view.kind === 'blocked'
          ? t('people.blocked')
          : t('people.title');

  return createPortal(
    <div
      role="dialog"
      aria-modal="true"
      aria-label={topTitle}
      data-testid="people-screen"
      className="fixed inset-0 z-[90] bg-canvas text-ink font-sans flex flex-col ph-screen-in"
    >
      <TopBar title={topTitle} onBack={stepBack} backLabel={t('formSheet.back')} safeTop />

      <div ref={stretchRef} className="flex-1 min-h-0 overflow-y-auto no-scrollbar pb-[calc(var(--sab,0px)+24px)]">
        {view.kind === 'home' && (
          <div data-testid="people-home">
            <h3 className="px-4 pt-4 pb-2 text-footnote font-semibold text-ink-2">{t('people.circles')}</h3>
            <DoorGroup>
              {circles.map((c) => {
                const members = membersOf(c.id);
                const count = members.length;
                return (
                  <DoorRow
                    key={c.id}
                    icon={<Users {...ICON} />}
                    label={circleName(c, t)}
                    onClick={() => {
                      soundFx.playTap();
                      setView({ kind: 'circle', circleId: c.id });
                    }}
                    trailing={
                      <span className="flex items-center gap-2 text-subhead text-ink-3 tabular-nums">
                        {count > 0 && (
                          <span data-testid={`circle-faces-${c.id}`} aria-hidden className="flex items-center -space-x-1.5">
                            {members.slice(0, 3).map((m) => (
                              <span key={m.id} data-face className="rounded-full ring-2 ring-surface">
                                <Avatar
                                  src={m.avatar}
                                  name={displayTitle(m.title)}
                                  tone={m.peerNodeId || m.id}
                                  radius="rounded-full"
                                  className="w-7 h-7 text-caption"
                                />
                              </span>
                            ))}
                          </span>
                        )}
                        <span data-testid={`circle-count-${c.id}`} className={count > 0 ? 'text-ink-2' : 'text-ink-3'}>
                          {count}
                        </span>
                        <ChevronRight className="w-4 h-4" strokeWidth={1.75} aria-hidden />
                      </span>
                    }
                  />
                );
              })}
            </DoorGroup>

            <DoorGroup className="mt-4">
              <DoorRow
                icon={<Eye {...ICON} />}
                label={t('people.seeAs')}
                onClick={() => {
                  soundFx.playTap();
                  setView({ kind: 'seeas', circleId: circles[0]?.id ?? 'friends' });
                }}
                trailing={<ChevronRight className="w-4 h-4 text-ink-3" strokeWidth={1.75} aria-hidden />}
              />
              <button
                type="button"
                data-testid="people-requests-row"
                onClick={() => {
                  soundFx.playTap();
                  setView({ kind: 'requests' });
                }}
                className="w-full min-h-12 px-4 py-1.5 flex items-center gap-4 text-left hover:bg-overlay/60 active:bg-overlay transition-colors duration-quick ease-standard"
              >
                <span className="w-5 shrink-0 flex items-center justify-center text-ink-2">
                  <Inbox {...ICON} />
                </span>
                <span className="flex-1 min-w-0 text-subhead text-ink">{t('people.requests')}</span>
                <span className="flex items-center gap-1.5 text-subhead text-ink-3 tabular-nums">
                  {incoming.length > 0 ? <span data-testid="people-requests-count">{incoming.length}</span> : null}
                  <ChevronRight className="w-4 h-4" strokeWidth={1.75} aria-hidden />
                </span>
              </button>
              <button
                type="button"
                data-testid="people-blocked-row"
                onClick={() => {
                  soundFx.playTap();
                  setView({ kind: 'blocked' });
                }}
                className="w-full min-h-12 px-4 py-1.5 flex items-center gap-4 text-left hover:bg-overlay/60 active:bg-overlay transition-colors duration-quick ease-standard"
              >
                <span className="w-5 shrink-0 flex items-center justify-center text-ink-2">
                  <Ban {...ICON} />
                </span>
                <span className="flex-1 min-w-0 text-subhead text-ink">{t('people.blocked')}</span>
                <span className="flex items-center gap-1.5 text-subhead text-ink-3 tabular-nums">
                  {blocked && blocked.length > 0 ? <span>{blocked.length}</span> : null}
                  <ChevronRight className="w-4 h-4" strokeWidth={1.75} aria-hidden />
                </span>
              </button>
            </DoorGroup>
          </div>
        )}

        {view.kind === 'circle' && (
          <div data-testid="circle-detail" className="min-h-full">
            {!(circlesLoading || loadingProp || circlesError || errorProp) && (
              <div className="pt-2">
                <DoorGroup>
                  <DoorRow
                    icon={<Shield {...ICON} />}
                    label={t('people.rights')}
                    onClick={() => {
                      soundFx.playTap();
                      setView({ kind: 'rights', circleId: view.circleId });
                    }}
                    trailing={<ChevronRight className="w-4 h-4 text-ink-3" strokeWidth={1.75} aria-hidden />}
                  />
                  <DoorRow
                    icon={<Eye {...ICON} />}
                    label={t('people.seeAs')}
                    onClick={() => {
                      soundFx.playTap();
                      setView({ kind: 'seeas', circleId: view.circleId });
                    }}
                    trailing={<ChevronRight className="w-4 h-4 text-ink-3" strokeWidth={1.75} aria-hidden />}
                  />
                </DoorGroup>
              </div>
            )}
            {circlesLoading || loadingProp ? (
              <div data-testid="circle-loading" className="px-4 pt-4 flex flex-col gap-3">
                {[0, 1, 2, 3].map((i) => (
                  <div key={i} className="h-14 px-4 rounded-card bg-surface flex items-center gap-3">
                    <span className="w-10 h-10 rounded-full bg-raised shrink-0" />
                    <span className="flex-1 flex flex-col gap-2">
                      <span className="w-2/5 h-3.5 rounded-control bg-raised" />
                      <span className="w-1/4 h-3 rounded-control bg-raised" />
                    </span>
                  </div>
                ))}
              </div>
            ) : circlesError || errorProp ? (
              <EmptyState
                testId="circle-error"
                icon={<Users className="w-6 h-6" strokeWidth={1.75} />}
                label={t('people.circlesError')}
                action={{
                  label: t('people.retry'),
                  onClick: () => {
                    setCirclesLoading(false);
                    loadCircles();
                  },
                }}
              />
            ) : circleMembers.length === 0 ? (
              <EmptyState
                testId="circle-empty"
                icon={<Users className="w-6 h-6" strokeWidth={1.75} />}
                label={t('people.circleEmpty')}
              />
            ) : (
              <div>
                <div data-testid="circle-header" className="px-4 pt-2 pb-1 flex items-center justify-between">
                  <div data-testid="circle-avatar-stack" className="flex items-center -space-x-2">
                    {circleMembers.slice(0, 5).map((m) => (
                      <Avatar
                        key={m.id}
                        src={m.avatar}
                        name={displayTitle(m.title)}
                        tone={m.peerNodeId || m.id}
                        radius="rounded-full"
                        className="w-8 h-8 text-caption ring-2 ring-canvas"
                      />
                    ))}
                  </div>
                  <span className="text-footnote text-ink-2 tabular-nums">
                    {t(pluralKey(locale, circleMembers.length, 'people.count'), { count: circleMembers.length })}
                  </span>
                </div>

                <h3 className="px-4 pt-4 pb-2 text-footnote font-semibold text-ink-2">
                  {t('people.membersCount', { count: circleMembers.length })}
                </h3>

                <DoorGroup>
                  {circleMembers.map((m) => {
                    const settled = restoredIds.includes(m.id);
                    const memberName = displayTitle(m.title);
                    return (
                      <button
                        key={m.id}
                        type="button"
                        data-testid={`circle-member-${m.id}`}
                        data-motion={settled ? (settleSpec.end ? 'end' : 'settle') : undefined}
                        style={
                          settled && !settleSpec.end
                            ? ({ '--np-ms': `${settleSpec.ms}ms`, '--np-ease': settleSpec.easing } as React.CSSProperties)
                            : undefined
                        }
                        onClick={() => {
                          soundFx.playTap();
                          setActiveMember({ chat: m, circleId: view.circleId });
                        }}
                        className={`w-full min-h-14 px-4 py-1.5 flex items-center gap-3 text-left hover:bg-overlay/60 active:bg-overlay transition-colors duration-quick ease-standard${
                          settled && !settleSpec.end ? ' np-settle' : ''
                        }`}
                      >
                        <Avatar
                          src={m.avatar}
                          name={memberName}
                          tone={m.peerNodeId || m.id}
                          radius="rounded-full"
                          className="w-10 h-10 text-subhead"
                        />
                        <span className="flex-1 min-w-0 truncate text-body text-ink">{memberName}</span>
                        <ChevronRight className="w-4 h-4 text-ink-3" strokeWidth={1.75} aria-hidden />
                      </button>
                    );
                  })}
                </DoorGroup>

                {!foldSpec.end &&
                  foldedIds.map((id) => (
                    <div
                      key={`fold-${id}`}
                      data-fold-row={id}
                      data-motion="fold"
                      aria-hidden="true"
                      onTransitionEnd={() => setFoldedIds((prev) => prev.filter((x) => x !== id))}
                      style={{
                        height: 0,
                        opacity: 0,
                        overflow: 'hidden',
                        transition: `height ${foldSpec.ms}ms ${foldSpec.easing}, opacity ${foldSpec.ms}ms ${foldSpec.easing}`,
                      }}
                    />
                  ))}
              </div>
            )}
          </div>
        )}

        {view.kind === 'rights' && activeCircle && 'builtin' in activeCircle && (
          <CircleRightsView circle={activeCircle} memberCount={membersOf(view.circleId).length} />
        )}

        {view.kind === 'seeas' && <SeeAsView circles={circles} initialId={view.circleId} />}

        {view.kind === 'requests' && (
          <div data-testid="requests-screen" className="min-h-full">
            {requestsError && incoming.length === 0 && outgoing.length === 0 ? (
              <EmptyState
                testId="requests-error"
                icon={<Inbox className="w-6 h-6" strokeWidth={1.75} />}
                label={t('people.blockedError')}
                action={{ label: t('people.retry'), onClick: () => void useRequestsStore.getState().load() }}
              />
            ) : requestsLoaded && incoming.length === 0 && outgoing.length === 0 ? (
              <EmptyState
                testId="requests-empty"
                icon={<Inbox className="w-6 h-6" strokeWidth={1.75} />}
                label={t('people.requestsEmpty')}
              />
            ) : (
              <div className="pt-2">
                {incoming.length > 0 && (
                  <DoorGroup>
                    {incoming.map((r) => {
                      const label = personName(r.name, t);
                      const facts = personFacts(r.peer_node_id, chats, circles, t);
                      const settled = restoredIds.includes(r.id);
                      return (
                        <div
                          key={r.id}
                          data-testid={`request-row-${r.id}`}
                          data-motion={settled ? (settleSpec.end ? 'end' : 'settle') : undefined}
                          style={
                            settled && !settleSpec.end
                              ? ({ '--np-ms': `${settleSpec.ms}ms`, '--np-ease': settleSpec.easing } as React.CSSProperties)
                              : undefined
                          }
                          className={`min-h-14 px-4 py-2 flex flex-col gap-2${settled && !settleSpec.end ? ' np-settle' : ''}`}
                        >
                          <button
                            type="button"
                            data-testid={`request-person-${r.id}`}
                            onClick={() => setRequestPerson({ peer: r.peer_node_id, name: label, avatar: facts.avatar, chatId: facts.chat?.id })}
                            className="w-full min-h-11 flex items-center gap-3 text-left"
                          >
                            <Avatar src={facts.avatar} name={label} tone={r.peer_node_id} radius="rounded-full" className="w-10 h-10 text-subhead" />
                            <span className="flex-1 min-w-0">
                              <span className="block truncate text-body font-semibold text-ink">{label}</span>
                              {facts.line && <span data-person-line className="block truncate text-footnote text-ink-2">{facts.line}</span>}
                              {r.text && <span className="block truncate text-footnote text-ink-2">{r.text}</span>}
                            </span>
                          </button>
                          {choosingId === r.id ? (
                            <div data-testid={`request-circles-${r.id}`} className="flex flex-wrap gap-2">
                              {circles.map((c) => (
                                <button
                                  key={c.id}
                                  type="button"
                                  data-testid={`request-circle-${r.id}-${c.id}`}
                                  onClick={() => stageRequest(r.id, 'accept', label, c.id)}
                                  className="h-11 px-3 rounded-control bg-overlay text-footnote font-semibold text-ink active:bg-raised"
                                >
                                  {circleName(c, t)}
                                </button>
                              ))}
                            </div>
                          ) : (
                            <div className="flex gap-2">
                              <button
                                type="button"
                                data-testid={`request-accept-${r.id}`}
                                onClick={() => setChoosingId(r.id)}
                                className="h-11 px-4 rounded-control bg-accent-soft text-accent-text text-footnote font-semibold active:opacity-80"
                              >
                                {t('people.requestAccept')}
                              </button>
                              <button
                                type="button"
                                data-testid={`request-decline-${r.id}`}
                                onClick={() => stageRequest(r.id, 'decline', label)}
                                className="h-11 px-4 rounded-control bg-overlay text-footnote font-semibold text-ink active:bg-raised"
                              >
                                {t('people.requestDecline')}
                              </button>
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </DoorGroup>
                )}
                {outgoing.length > 0 && (
                  <>
                    <h3 className="px-4 pt-4 pb-2 text-footnote font-semibold text-ink-2">{t('people.requestsSent')}</h3>
                    <DoorGroup>
                      {outgoing.map((r) => {
                        const label = personName(r.name, t);
                        const facts = personFacts(r.peer_node_id, chats, circles, t);
                        const settled = restoredIds.includes(r.id);
                        return (
                          <div
                            key={r.id}
                            data-testid={`request-sent-${r.id}`}
                            data-motion={settled ? (settleSpec.end ? 'end' : 'settle') : undefined}
                            className={`min-h-14 px-4 py-1.5 flex items-center gap-3${settled && !settleSpec.end ? ' np-settle' : ''}`}
                          >
                            <button
                              type="button"
                              data-testid={`request-sent-person-${r.id}`}
                              onClick={() => setRequestPerson({ peer: r.peer_node_id, name: label, avatar: facts.avatar, chatId: facts.chat?.id })}
                              className="flex-1 min-w-0 min-h-11 flex items-center gap-3 text-left"
                            >
                              <Avatar src={facts.avatar} name={label} tone={r.peer_node_id} radius="rounded-full" className="w-10 h-10 text-subhead" />
                              <span className="flex-1 min-w-0">
                                <span className="block truncate text-body font-semibold text-ink">{label}</span>
                                {facts.line && <span className="block truncate text-footnote text-ink-2">{facts.line}</span>}
                              </span>
                            </button>
                            <button
                              type="button"
                              data-testid={`request-withdraw-${r.id}`}
                              onClick={() => stageRequest(r.id, 'withdraw', label)}
                              className="h-11 px-3 rounded-control bg-overlay text-footnote font-semibold text-ink active:bg-raised"
                            >
                              {t('people.requestWithdraw')}
                            </button>
                          </div>
                        );
                      })}
                    </DoorGroup>
                  </>
                )}
                {!foldSpec.end &&
                  foldedIds.map((id) => (
                    <div
                      key={`fold-${id}`}
                      data-fold-row={id}
                      data-motion="fold"
                      aria-hidden="true"
                      onTransitionEnd={() => setFoldedIds((prev) => prev.filter((x) => x !== id))}
                      style={{
                        height: 0,
                        opacity: 0,
                        overflow: 'hidden',
                        transition: `height ${foldSpec.ms}ms ${foldSpec.easing}, opacity ${foldSpec.ms}ms ${foldSpec.easing}`,
                      }}
                    />
                  ))}
              </div>
            )}
          </div>
        )}

        {view.kind === 'blocked' && (
          <div data-testid="blocked-screen" className="min-h-full">
            {blockedError ? (
              <EmptyState
                testId="blocked-error"
                icon={<Ban className="w-6 h-6" strokeWidth={1.75} />}
                label={t('people.blockedError')}
                action={{ label: t('people.retry'), onClick: reloadBlocked }}
              />
            ) : !blockedLoading && blocked && blocked.length === 0 ? (
              <EmptyState
                testId="blocked-empty"
                icon={<Ban className="w-6 h-6" strokeWidth={1.75} />}
                label={t('people.blockedEmpty')}
              />
            ) : blocked && blocked.length > 0 ? (
              <div className="pt-2">
                <DoorGroup>
                  {blocked.map((b) => {
                    const settled = restoredIds.includes(b.id);
                    const labelName = personName(b.name, t);
                    return (
                      <div
                        key={b.id}
                        data-testid={`blocked-row-${b.id}`}
                        data-motion={settled ? (settleSpec.end ? 'end' : 'settle') : undefined}
                        style={
                          settled && !settleSpec.end
                            ? ({ '--np-ms': `${settleSpec.ms}ms`, '--np-ease': settleSpec.easing } as React.CSSProperties)
                            : undefined
                        }
                        className={`min-h-14 px-4 py-1.5 flex items-center gap-3${
                          settled && !settleSpec.end ? ' np-settle' : ''
                        }`}
                      >
                        <Avatar
                          name={labelName}
                          tone={b.id}
                          radius="rounded-full"
                          className="w-10 h-10 text-subhead"
                        />
                        <span className="flex-1 min-w-0 truncate text-body text-ink">{labelName}</span>
                        <button
                          type="button"
                          onClick={() => void handleUnblockPeer(b)}
                          className="h-11 px-3 rounded-control bg-overlay text-footnote font-semibold text-ink active:bg-raised"
                        >
                          {t('people.unblock')}
                        </button>
                      </div>
                    );
                  })}
                </DoorGroup>
                {!foldSpec.end &&
                  foldedIds.map((id) => (
                    <div
                      key={`fold-${id}`}
                      data-fold-row={id}
                      data-motion="fold"
                      aria-hidden="true"
                      onTransitionEnd={() => setFoldedIds((prev) => prev.filter((x) => x !== id))}
                      style={{
                        height: 0,
                        opacity: 0,
                        overflow: 'hidden',
                        transition: `height ${foldSpec.ms}ms ${foldSpec.easing}, opacity ${foldSpec.ms}ms ${foldSpec.easing}`,
                      }}
                    />
                  ))}
              </div>
            ) : null}
          </div>
        )}
      </div>

      {requestPerson && (
        <PeerProfileSheet
          peerNodeId={requestPerson.peer}
          name={requestPerson.name}
          avatar={requestPerson.avatar}
          chatId={requestPerson.chatId}
          onClose={() => setRequestPerson(null)}
        />
      )}

      <PersonActionsSheet
        chat={activeMember?.chat ?? null}
        circleId={activeMember?.circleId ?? ''}
        onClose={() => setActiveMember(null)}
        onRemoveFromCircle={handleRemoveFromCircle}
        onBlockPeer={handleBlockPeer}
      />

      {snack && <Toast message={snack.message} action={snack.action} onDone={() => setSnack(null)} />}
    </div>,
    document.body,
  );
};

export default PeopleScreen;
