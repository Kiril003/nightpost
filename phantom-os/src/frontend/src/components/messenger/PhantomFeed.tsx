/**
 * Профіль › PHANTOM: «Що я роблю» і «Стоп усе».
 *
 * Стрічка — незмінна (`symbiote/feed.py`, ланцюжок хешів): кожна дія PHANTOM,
 * ваша і ваших пристроїв, окремим рядком. Нічого не переписується; якщо
 * ланцюжок розірвано, тут так і написано. На ПК відповідає вузол, на
 * телефоні — міст застосунку; немає відповіді — немає частини.
 *
 * «Стоп усе» — одна дія: розум і тіла перестають діяти. Знову дозволити
 * може лише людина тут, у вікні.
 *
 * Доручення («PHANTOM, зроби …» — двигун «Кузня»): ціль, скільки кроків
 * доведено системою, і дія назовні, що чекає вашого «так». Керує вузол (ПК);
 * телефон доручення бачить, а відповідає на них компʼютер.
 */
import React, { useCallback, useEffect, useState } from "react";
import { Activity, ChevronRight, Hand } from "lucide-react";
import { soundFx } from "../../utils/messengerSound";
import { SectionTitle } from "./MeSections";
import {
  deviceApi,
  feedWords,
  hereIsPhone,
  lineTime,
  missionWords,
  type Feed,
  type MissionLine,
} from "../../services/deviceApi";
import { useTranslation } from '../../i18n/useTranslation';

/** Скільки чекати між дочитуваннями, поки сторінка відкрита. */
export const FEED_POLL_MS = 4000;

const useFeed = (poll: boolean) => {
  const [feed, setFeed] = useState<Feed | null>(null);
  const [gone, setGone] = useState(false);
  const refresh = useCallback(async () => {
    try {
      setFeed(await deviceApi.feed());
      setGone(false);
    } catch {
      setGone(true);
    }
  }, []);
  useEffect(() => {
    void refresh();
    if (!poll) return;
    const id = window.setInterval(() => void refresh(), FEED_POLL_MS);
    return () => window.clearInterval(id);
  }, [poll, refresh]);
  return { feed, gone, refresh, setFeed };
};

/** Розділ «PHANTOM» Профілю: згрупований список — стрічка і вимикач. */
export const PhantomRows: React.FC<{ onFeed: () => void }> = ({ onFeed }) => {
  const { t } = useTranslation();
  const { feed, setFeed } = useFeed(false);
  const [busy, setBusy] = useState(false);
  if (!feed) return null;

  const toggle = async () => {
    setBusy(true);
    soundFx.playTap();
    try {
      const r = await deviceApi.stopAll(!feed.stopped);
      setFeed({ ...feed, stopped: r.stopped });
    } catch {
      /* стан лишається тим, що сказав вузол */
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <SectionTitle>Nightpost</SectionTitle>
      <div
        data-testid="me-phantom"
        className="mx-4 rounded-card bg-raised overflow-hidden"
      >
        <button
          type="button"
          data-testid="me-feed"
          onClick={onFeed}
          className="w-full min-h-14 px-4 flex items-center gap-3 text-left hover:bg-overlay/60 active:bg-overlay"
        >
          <Activity
            className="w-5 h-5 shrink-0 text-ink-2"
            strokeWidth={1.75}
            aria-hidden="true"
          />
          <span className="flex-1 text-body text-ink">{t('phantomFeed.whatIDo')}</span>
          <span className="shrink-0 text-caption text-ink-3">
            {feed.entries.length || ""}
          </span>
          <ChevronRight
            className="w-4 h-4 text-ink-3"
            strokeWidth={1.75}
            aria-hidden="true"
          />
        </button>
        <div aria-hidden className="ml-12 h-px bg-line" />
        <button
          type="button"
          role="switch"
          aria-checked={feed.stopped}
          data-testid="me-stop-all"
          data-stopped={feed.stopped ? "on" : "off"}
          disabled={busy}
          onClick={() => void toggle()}
          className="w-full min-h-14 px-4 flex items-center gap-3 text-left hover:bg-overlay/60 active:bg-overlay disabled:opacity-60"
        >
          <Hand
            className={`w-5 h-5 shrink-0 ${feed.stopped ? "text-danger" : "text-ink-2"}`}
            strokeWidth={1.75}
            aria-hidden="true"
          />
          <span className="flex-1 min-w-0">
            <span className="block text-body text-ink">{t('phantomFeed.stopAll')}</span>
            <span className="block text-caption text-ink-3">
              {feed.stopped
                ? t('phantomFeed.stoppedHint')
                : t('phantomFeed.activeHint')}
            </span>
          </span>
          <span
            className={`shrink-0 text-caption ${feed.stopped ? "text-danger" : "text-ink-2"}`}
          >
            {feed.stopped ? t('phantomFeed.on') : t('phantomFeed.off')}
          </span>
        </button>
      </div>
    </>
  );
};

const LIVE = new Set(["running", "paused", "asking"]);

const MissionButton: React.FC<{
  label: string;
  tone?: "accent" | "plain";
  busy: boolean;
  onClick: () => void;
  testId: string;
}> = ({ label, tone = "plain", busy, onClick, testId }) => (
  <button
    type="button"
    data-testid={testId}
    disabled={busy}
    onClick={onClick}
    className={`h-11 min-h-0 px-4 rounded-control text-subhead disabled:opacity-60 ${
      tone === "accent" ? "bg-accent text-on-accent" : "bg-overlay text-ink active:bg-raised"
    }`}
  >
    {label}
  </button>
);

/** Що саме PHANTOM хоче зробити назовні — щоб «так» було свідомим. */
const askText = (m: MissionLine): string => {
  const text = m.ask?.args?.text;
  return typeof text === "string" && text ? `«${text}»` : "";
};

const Missions: React.FC<{ missions: MissionLine[]; onChanged: () => void }> = ({
  missions,
  onChanged,
}) => {
  const { t } = useTranslation();
  const [busy, setBusy] = useState<string | null>(null);
  const here = !hereIsPhone();
  const act = async (id: string, fn: () => Promise<unknown>) => {
    setBusy(id);
    soundFx.playTap();
    try {
      await fn();
    } catch {
      /* стан покаже наступне дочитування */
    } finally {
      setBusy(null);
      onChanged();
    }
  };
  return (
    <section data-testid="feed-missions" className="mt-3">
      <h3 className="px-4 pb-2 text-footnote font-semibold text-ink-2">{t('phantomFeed.missions')}</h3>
      <ul className="mx-4 rounded-card bg-raised overflow-hidden">
        {missions.map((m, i) => {
          const w = missionWords(m);
          const live = LIVE.has(m.status);
          return (
            <li key={m.id} data-mission={m.status} className="px-4">
              {i > 0 && <div aria-hidden className="h-px bg-line" />}
              <div className="py-3">
                <span className="block text-body text-ink line-clamp-2">{m.goal}</span>
                <span
                  className={`block text-caption ${m.status === "failed" ? "text-danger" : "text-ink-3"}`}
                >
                  {[w.state, w.progress, live ? m.waiting : m.error].filter(Boolean).join(" · ")}
                </span>
                {w.ask && (
                  <p data-testid="mission-ask" className="mt-1 text-callout text-ink">
                    {w.ask} {askText(m)}
                  </p>
                )}
                {live && here && (
                  <div className="mt-2 flex flex-wrap gap-2">
                    {m.status === "asking" && (
                      <>
                        <MissionButton
                          testId="mission-yes"
                          label={t('phantomFeed.yes')}
                          tone="accent"
                          busy={busy === m.id}
                          onClick={() => void act(m.id, () => deviceApi.answerMission(m.id, true))}
                        />
                        <MissionButton
                          testId="mission-no"
                          label={t('phantomFeed.no')}
                          busy={busy === m.id}
                          onClick={() => void act(m.id, () => deviceApi.answerMission(m.id, false))}
                        />
                      </>
                    )}
                    {m.status === "running" && (
                      <MissionButton
                        testId="mission-pause"
                        label={t('phantomFeed.pause')}
                        busy={busy === m.id}
                        onClick={() => void act(m.id, () => deviceApi.mission(m.id, "pause"))}
                      />
                    )}
                    {m.status === "paused" && (
                      <MissionButton
                        testId="mission-resume"
                        label={t('phantomFeed.next')}
                        busy={busy === m.id}
                        onClick={() => void act(m.id, () => deviceApi.mission(m.id, "resume"))}
                      />
                    )}
                    <MissionButton
                      testId="mission-cancel"
                      label={t('phantomFeed.cancel')}
                      busy={busy === m.id}
                      onClick={() => void act(m.id, () => deviceApi.mission(m.id, "cancel"))}
                    />
                  </div>
                )}
                {live && !here && m.status === "asking" && (
                  <span className="block mt-1 text-caption text-ink-3">
                    {t('phantomFeed.answerOnPc')}
                  </span>
                )}
              </div>
            </li>
          );
        })}
      </ul>
    </section>
  );
};

/** Сторінка «Що я роблю» — живе, поки відкрита. */
export const PhantomFeed: React.FC = () => {
  const { t } = useTranslation();
  const { feed, gone, refresh } = useFeed(true);
  if (gone && !feed) {
    return (
      <p className="px-4 py-6 text-callout text-ink-3">
        {t('phantomFeed.unavailable')}
      </p>
    );
  }
  if (!feed) return null;
  return (
    <div className="pb-6" data-testid="phantom-feed">
      {feed.stopped && (
        <p
          role="status"
          className="mx-4 mt-2 px-3 py-2 rounded-control bg-raised text-callout text-danger"
        >
          {t('phantomFeed.allStopped')}
        </p>
      )}
      {feed.missions && feed.missions.length > 0 && (
        <Missions missions={feed.missions} onChanged={() => void refresh()} />
      )}
      <p
        data-testid="feed-chain"
        data-chain={feed.chain.ok ? "ok" : "broken"}
        className={`px-4 pt-3 text-caption ${feed.chain.ok ? "text-ink-3" : "text-danger"}`}
      >
        {feed.chain.ok
          ? t('phantomFeed.chainOk')
          : t('phantomFeed.chainBroken', { broken_at: feed.chain.broken_at ?? "?" })}
      </p>
      {feed.offline ? (
        <p data-testid="feed-offline" className="px-4 py-6 text-callout text-ink-3">
          {t('phantomFeed.offline')}
        </p>
      ) : feed.entries.length === 0 ? (
        <p className="px-4 py-6 text-callout text-ink-3">
          {t('phantomFeed.empty')}
        </p>
      ) : (
        <ul className="mt-2">
          {feed.entries.map((e, i) => {
            const words = feedWords(e);
            return (
              <li key={e.n} data-feed-line={e.who} className="px-4">
                {i > 0 && <div aria-hidden className="h-px bg-line" />}
                <div className="py-3 flex items-baseline gap-3">
                  <span className="flex-1 min-w-0">
                    <span className="block text-body text-ink">
                      {words.what}
                    </span>
                    {words.state && (
                      <span className="block text-caption text-ink-3">
                        {words.state}
                      </span>
                    )}
                  </span>
                  <span className="shrink-0 text-caption text-ink-3 tabular-nums">
                    {lineTime(e.at_iso)}
                  </span>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
};

export default PhantomFeed;
