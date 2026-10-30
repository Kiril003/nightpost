/**
 * «Спільне» розмови — одна копія для профілю людини й профілю групи.
 * Вкладки лише непорожні; немає нічого — один рядок.
 */
import React, { useEffect, useState } from 'react';
import { CalendarDays, FileText, Image as ImageIcon, Link2, MapPin, MessageCircle, Mic, Receipt } from 'lucide-react';
import { SHARED_TABS } from '../../services/peerShared';
import type { SharedIndex, SharedTab } from '../../services/peerShared';
import { cachedMediaUrl, humanSize, loadMedia } from '../../services/messengerMedia';
import type { Message } from '../../types/messenger';
import { useTranslation } from '../../i18n/useTranslation';
import { dateTag } from '../../i18n/dateTag';
import { chipTone } from './kit/FilterChip';

const ICO = { className: 'w-5 h-5', strokeWidth: 1.75 } as const;


export const shortDate = (iso: string, locale: string = 'uk'): string => {
  const t = Date.parse(iso);
  return Number.isFinite(t) ? new Date(t).toLocaleDateString(dateTag(locale), { day: 'numeric', month: 'short' }) : '';
};

/** Адреса фото з листа: з кешу одразу, інакше — підвантажити. */
export function useMediaUrl(msg: Message): { url: string | undefined; missing: boolean } {
  const media = msg.media;
  const [url, setUrl] = useState<string | undefined>(() => (media ? cachedMediaUrl(media.blobId) : msg.mediaUrl ?? msg.imageData?.url));
  const [missing, setMissing] = useState(false);

  useEffect(() => {
    if (!media || url) return;
    let alive = true;
    loadMedia(media)
      .then((u) => alive && setUrl(u))
      .catch(() => alive && setMissing(true));
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [media?.blobId]);
  return { url, missing };
}

/** Мініатюра 40 для рядка «Спільне»: фото або тихий квадрат, без кнопки. */
export const MediaThumb: React.FC<{ msg: Message }> = ({ msg }) => {
  const { url } = useMediaUrl(msg);
  return (
    <span className="w-10 h-10 shrink-0 overflow-hidden rounded-control bg-raised" data-testid="shared-thumb">
      {url && msg.type !== 'video-note' ? <img src={url} alt="" className="w-full h-full object-cover" loading="lazy" /> : null}
    </span>
  );
};

export const MediaCell: React.FC<{ msg: Message; onOpen: (url: string) => void; onJump: () => void }> = ({ msg, onOpen, onJump }) => {
  const { t } = useTranslation();
  const { url, missing } = useMediaUrl(msg);

  const isVideo = msg.type === 'video-note';
  return (
    <button
      type="button"
      data-testid="shared-media-cell"
      onClick={() => (url && !isVideo ? onOpen(url) : onJump())}
      className="relative aspect-square overflow-hidden bg-raised"
      aria-label={isVideo ? t('sharedSection.videoNote') : t('sharedSection.photo')}
    >
      {url && !isVideo ? (
        <img src={url} alt="" className="w-full h-full object-cover" loading="lazy" />
      ) : (
        <span className="absolute inset-0 flex items-center justify-center text-ink-3">
          {isVideo ? <MessageCircle className="w-5 h-5" strokeWidth={1.75} /> : missing ? <ImageIcon className="w-5 h-5" strokeWidth={1.75} /> : null}
        </span>
      )}
    </button>
  );
};

/** Вміст однієї вкладки «Спільного»: і для старої смуги, і для вкладок InfoView. */
export const SharedPanel: React.FC<{
  shared: SharedIndex;
  tab: SharedTab;
  whoSent: (msg: Message) => string;
  onJump: (msgId: string) => void;
  onOpenImage: (url: string) => void;
}> = ({ shared, tab, whoSent, onJump, onOpenImage }) => {
  const { t, locale } = useTranslation();
  const activeTab = tab;
  const tabItems = shared[tab];
  if (tabItems.length === 0) {
    return (
      <p className="px-4 py-12 text-center text-subhead text-ink-2" data-testid={`shared-empty-${tab}`}>
        {SHARED_TABS.find((x) => x.key === tab)?.empty}
      </p>
    );
  }
  return (
    <div className="px-4 pt-3">
      {activeTab === 'media' ? (
        <div className="grid grid-cols-3 gap-1 rounded-card overflow-hidden">
          {shared.media.slice(0, 60).map((m) => (
            <MediaCell key={m.id} msg={m} onOpen={onOpenImage} onJump={() => onJump(m.id)} />
          ))}
        </div>
      ) : activeTab === 'links' ? (
        <ul>
          {shared.links.map((l) => (
            <li key={l.url}>
              <button type="button" onClick={() => onJump(l.msgId)} className="w-full min-h-[52px] flex items-center gap-3 text-left">
                <span className="w-10 h-10 shrink-0 rounded-control bg-raised flex items-center justify-center text-ink-2">
                  <Link2 {...ICO} />
                </span>
                <span className="flex-1 min-w-0">
                  <span className="block text-headline text-ink truncate">{l.host}</span>
                  <span className="block text-caption text-ink-3 truncate">{l.url}</span>
                </span>
                <span className="text-caption text-ink-3 shrink-0">{shortDate(l.at, locale)}</span>
              </button>
            </li>
          ))}
        </ul>
      ) : activeTab === 'payments' || activeTab === 'meetups' ? (
        <ul>
          {shared[activeTab].map((c) => (
            <li key={c.msgId}>
              <button
                type="button"
                data-testid={`shared-${activeTab}-row`}
                onClick={() => onJump(c.msgId)}
                className="w-full min-h-[52px] flex items-center gap-3 text-left"
              >
                <span className="w-10 h-10 shrink-0 rounded-control bg-raised flex items-center justify-center text-ink-2">
                  {activeTab === 'payments' ? <Receipt {...ICO} /> : <CalendarDays {...ICO} />}
                </span>
                <span className="flex-1 min-w-0">
                  <span className="block text-headline text-ink truncate">{c.title}</span>
                  {c.sub && <span className="block text-caption text-ink-3 truncate">{c.sub}</span>}
                </span>
                {/* У зустрічі день уже в рядку; дата надсилання поруч була б другою датою. */}
                {activeTab === 'payments' && <span className="text-caption text-ink-3 shrink-0">{shortDate(c.at, locale)}</span>}
              </button>
            </li>
          ))}
        </ul>
      ) : activeTab === 'places' ? (
        <ul>
          {shared.places.map((p) => (
            <li key={p.msgId}>
              <button type="button" onClick={() => onJump(p.msgId)} className="w-full min-h-[52px] flex items-center gap-3 text-left">
                <span className="w-10 h-10 shrink-0 rounded-control bg-raised flex items-center justify-center text-ink-2">
                  <MapPin {...ICO} />
                </span>
                <span className="flex-1 min-w-0">
                  <span className="block text-headline text-ink truncate">
                    {p.label}
                    {p.mine ? t('sharedSection.mineSuffix') : ''}
                  </span>
                </span>
                <span className="text-caption text-ink-3 shrink-0">{shortDate(p.at, locale)}</span>
              </button>
            </li>
          ))}
        </ul>
      ) : (
        <ul>
          {tabItems.map((m) => {
            const msg = m as Message;
            const title =
              activeTab === 'voice'
                ? msg.voiceData?.duration
                  ? t('sharedSection.voiceSecs', { secs: Math.round(msg.voiceData.duration) })
                  : t('sharedSection.voice')
                : msg.media?.name || msg.fileData?.name || msg.fileName || t('sharedSection.file');
            const size = msg.media?.size ? humanSize(msg.media.size) : msg.fileData?.size || '';
            return (
              <li key={msg.id}>
                <button type="button" onClick={() => onJump(msg.id)} className="w-full min-h-[52px] flex items-center gap-3 text-left">
                  <span className="w-10 h-10 shrink-0 rounded-control bg-raised flex items-center justify-center text-ink-2">
                    {activeTab === 'voice' ? <Mic {...ICO} /> : <FileText {...ICO} />}
                  </span>
                  <span className="flex-1 min-w-0">
                    <span className="block text-headline text-ink truncate">{title}</span>
                    <span className="block text-caption text-ink-3 truncate">
                      {whoSent(msg)}
                      {size ? ` · ${size}` : ''}
                    </span>
                  </span>
                  <span className="text-caption text-ink-3 shrink-0">{shortDate(msg.sentAt || msg.timestamp, locale)}</span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
};

export const SharedSection: React.FC<{
  shared: SharedIndex;
  /** Хто надіслав файл чи голосове — підпис другого рядка. */
  whoSent: (msg: Message) => string;
  onJump: (msgId: string) => void;
  onOpenImage: (url: string) => void;
  testId?: string;
}> = ({ shared, whoSent, onJump, onOpenImage, testId }) => {
  const { t } = useTranslation();
  const [tab, setTab] = useState<SharedTab>('media');
  const activeTab: SharedTab = shared[tab].length > 0 ? tab : (SHARED_TABS.find((x) => shared[x.key].length > 0)?.key ?? tab);
  const tabsWithItems = SHARED_TABS.filter((x) => shared[x.key].length > 0);

  return (
    <div className="mt-6" data-testid={testId}>
      {tabsWithItems.length === 0 ? (
        <p className="px-4 text-callout text-ink-3" data-testid="shared-none">
          {t('sharedSection.empty')}
        </p>
      ) : (
        <>
          <div className="px-4 flex gap-1.5 overflow-x-auto no-scrollbar" role="tablist" aria-label={t('sharedSection.label')}>
            {tabsWithItems.map((tb) => (
              <button
                key={tb.key}
                role="tab"
                type="button"
                aria-selected={activeTab === tb.key}
                data-testid={`shared-tab-${tb.key}`}
                onClick={() => setTab(tb.key)}
                className="h-11 shrink-0 flex items-center"
              >
                <span
                  className={`h-[34px] px-3.5 rounded-full border flex items-center text-callout font-semibold transition-colors duration-instant ${chipTone(activeTab === tb.key)}`}
                >
                  {tb.label}
                  <span className="ml-1.5 opacity-[.55] tnum">{shared[tb.key].length}</span>
                </span>
              </button>
            ))}
          </div>

          <SharedPanel shared={shared} tab={activeTab} whoSent={whoSent} onJump={onJump} onOpenImage={onOpenImage} />
        </>
      )}
    </div>
  );
};
