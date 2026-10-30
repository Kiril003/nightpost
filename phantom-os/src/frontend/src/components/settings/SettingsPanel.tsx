import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  ArrowLeft,
  Save,
  RotateCcw,
  Loader2,
  Plug,
  CheckCircle2,
  AlertTriangle,
  GitCompareArrows,
  Sun,
  Moon,
  Cog,
  SlidersHorizontal as Tune,
  Languages,
  Search,
  X as XIcon,
  Globe,
} from 'lucide-react';
import { ProfileManagementSection } from './ProfileManagement';
import { MobilePairing } from './MobilePairing';
import { DirectoryConsentPanel } from '../messenger/DirectoryConsentPanel';
import { VaultPanel } from './VaultPanel';
import { BackupRestoreCard } from './BackupRestoreCard';
import {
  useSettingsStore,
  THEME_SETTING_KEY,
  LANGUAGE_SETTING_KEY,
} from '../../stores/settingsStore';
import { LOCALES, LOCALE_LABELS, type Locale } from '../../i18n';
import { useTranslation } from '../../i18n/useTranslation';
import {
  settingsApi,
  aiApi,
  type OllamaModelInfo,
  type AITestResponse,
} from '../../services/api';
import { applyUISettings } from '../../services/settingsBootstrap';
import type { SettingDefinition } from '@shared/types';
import { groupByInferredSubgroup } from './groupSettings';
import {
  SettingsAccordion,
  readAccordionState,
  writeAccordionState,
} from './SettingsAccordion';
import { AgentLimitsGroup } from './AgentLimitsGroup';
import { AgentLayoutGroup } from './AgentLayoutGroup';
import { DesktopShellGroup } from './DesktopShellGroup';
import { Monitor, KeyRound, ShieldCheck, Key, Users, Smartphone } from 'lucide-react';
import { KeyVaultPanel } from './KeyVaultPanel';
import { LicenseGroup } from './LicenseGroup';
import { ApiKeysTab } from './ApiKeysTab';
import { MembersTab } from './MembersTab';

type ThemeId = 'sunrise-warm' | 'amber-night' | 'cyberdeck-cold';

/* ─── SettingsPanel — sunrise repaint (phase-5-R1-FE-SET) ─────────────
 * Preserves ALL existing Zustand selectors, store actions, and API
 * calls (settingsApi.getAll/set/reset, applyUISettings, etc.). Only
 * the visual chrome and theme-picker tile UI are new. THEME-NIGHT
 * agent owns the active-theme writeback through the same setValue/
 * settingsApi.set pipeline; this file just renders the picker so the
 * operator can choose. */
export default function SettingsPanel() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const categories = useSettingsStore((s) => s.categories);
  const values = useSettingsStore((s) => s.values);
  const dirty = useSettingsStore((s) => s.dirty);
  const loaded = useSettingsStore((s) => s.loaded);
  const setCategories = useSettingsStore((s) => s.setCategories);
  const setValue = useSettingsStore((s) => s.setValue);
  const markClean = useSettingsStore((s) => s.markClean);
  // Phase 22 — IA: Basic / Advanced gate + live search.
  const showAdvanced = useSettingsStore((s) => s.showAdvanced);
  const setShowAdvanced = useSettingsStore((s) => s.setShowAdvanced);
  const query = useSettingsStore((s) => s.query);
  const setQuery = useSettingsStore((s) => s.setQuery);

  const { categoryId: urlCategoryId } = useParams<{ categoryId: string }>();
  const [activeCategoryId, setActiveCategoryId] = useState<string>('');
  const [status, setStatus] = useState<
    | { kind: 'idle' }
    | { kind: 'loading' }
    | { kind: 'saving' }
    | { kind: 'error'; msg: string }
    | { kind: 'saved' }
  >({ kind: 'idle' });
  const [accordionState, setAccordionState] = useState<
    Record<string, Record<string, boolean>>
  >({});

  useEffect(() => {
    let cancelled = false;
    setStatus({ kind: 'loading' });
    settingsApi
      .getAll()
      .then((data) => {
        if (cancelled) return;
        setCategories(data.categories);
        setActiveCategoryId((prev) => urlCategoryId || prev || data.categories[0]?.id || '');
        setStatus({ kind: 'idle' });
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setStatus({
          kind: 'error',
          msg: err instanceof Error ? err.message : 'Failed to load settings',
        });
      });
    return () => {
      cancelled = true;
    };
  }, [setCategories]);

  useEffect(() => {
    if (!activeCategoryId && categories.length > 0) {
      setActiveCategoryId(categories[0].id);
    }
  }, [categories, activeCategoryId]);

  const allCategories = useMemo(() => {
    const virtual: any = {
      id: 'desktop',
      label: t('setp.cat.desktop'),
      icon: <Monitor size={14} />,
      settings: [],
    };
    const polisKeys: any = {
      id: 'polis_keys',
      label: t('setp.cat.polisKeys'),
      icon: <KeyRound size={14} />,
      settings: [],
    };
    const license: any = {
      id: 'license',
      label: t('setp.cat.license'),
      icon: <ShieldCheck size={14} />,
      settings: [],
    };
    // Вкладки «Оплата» більше немає. Вона показувала зашитий рівень «Pro» і
    // 45 000 витрачених токенів — обидва числа вигадані просто в компоненті,
    // і жоден лічильник у системі їх не рахував. Правда про ліцензію одна і
    // живе у вкладці «Ліцензія»; продаж — на сайті, не в пристрої.
    const apiKeys: any = {
      id: 'api_keys',
      label: t('setp.cat.apiKeys'),
      icon: <Key size={14} />,
      settings: [],
    };
    const members: any = {
      id: 'members',
      label: t('setp.cat.members'),
      icon: <Users size={14} />,
      settings: [],
    };
    // Панель пари з телефоном була написана й НЕДОСЯЖНА: рендер чекав на
    // категорію `mobile`, а в списку її не було взагалі. Тобто єдиний шлях
    // дати ПК точне місце існував лише в коді.
    const mobile: any = {
      id: 'mobile',
      label: t('setp.cat.mobile'),
      icon: <Smartphone size={14} />,
      settings: [],
    };
    // Згода бути в довіднику. Окрема вкладка, а не рядок углибині «Мережі»:
    // це рішення про те, чи існує слово, за яким тебе можна знайти на
    // сервері, а не перемикач поведінки. Типово людини в довіднику НЕМАЄ.
    const directory: any = {
      id: 'directory',
      label: t('setp.cat.directory'),
      icon: <Globe size={14} />,
      settings: [],
    };
    // Бекенд теж віддає `mobile`, тому зводимо за id: дубль давав React
    // попередження про однакові ключі, а користувачу — дві однакові вкладки.
    const merged = [...categories, mobile, directory, polisKeys, license, virtual, apiKeys, members];
    const seen = new Set<string>();
    return merged.filter((c: any) => {
      if (seen.has(c.id)) return false;
      seen.add(c.id);
      return true;
    });
  }, [categories, t]);

  const activeCategory = useMemo(
    () => allCategories.find((c) => c.id === activeCategoryId),
    [allCategories, activeCategoryId]
  );

  const dirtyInCategory = useMemo(() => {
    if (!activeCategory) return [] as string[];
    return (activeCategory.settings || [])
      .map((d: any) => d.key)
      .filter((k: string) => dirty.has(k));
  }, [activeCategory, dirty]);

  /* ── Aggregate progress (configured / total) across all categories.
     A setting counts as "configured" if its current value differs from
     its registered default — otherwise it's pristine. Mirrors the
     "83 / 88" pill in the design comp. ────────────────────────────── */
  const overallProgress = useMemo(() => {
    let total = 0;
    let configured = 0;
    for (const cat of categories) {
      for (const def of cat.settings) {
        total += 1;
        const cur = values[def.key];
        if (cur !== undefined && JSON.stringify(cur) !== JSON.stringify(def.default)) {
          configured += 1;
        }
      }
    }
    return { total, configured, ratio: total === 0 ? 0 : configured / total };
  }, [categories, values]);

  const handleSave = useCallback(async () => {
    if (!activeCategory || dirtyInCategory.length === 0) return;
    setStatus({ kind: 'saving' });
    try {
      const appliedPatch: Record<string, unknown> = {};
      for (const key of dirtyInCategory) {
        await settingsApi.set(key, values[key]);
        appliedPatch[key] = values[key];
        markClean(key);
      }
      applyUISettings(appliedPatch);
      setStatus({ kind: 'saved' });
      setTimeout(() => setStatus({ kind: 'idle' }), 1200);
    } catch (err) {
      setStatus({
        kind: 'error',
        msg: err instanceof Error ? err.message : 'Save failed',
      });
    }
  }, [activeCategory, dirtyInCategory, values, markClean]);

  const handleReset = useCallback(async () => {
    if (!activeCategory) return;
    try {
      await settingsApi.reset(activeCategory.id);
      const fresh = await settingsApi.getAll();
      setCategories(fresh.categories);
      const all: Record<string, unknown> = {};
      for (const cat of fresh.categories) {
        for (const def of cat.settings) {
          all[def.key] = def.value;
        }
      }
      applyUISettings(all);
      setStatus({ kind: 'saved' });
      setTimeout(() => setStatus({ kind: 'idle' }), 1200);
    } catch (err) {
      setStatus({
        kind: 'error',
        msg: err instanceof Error ? err.message : t('setp.resetFailed'),
      });
    }
  }, [activeCategory, setCategories]);

  /* ── Pending-changes diff summary (3 PENDING CHANGES · …). Shows
     up to 2 keys verbatim then "+N more" so the strip stays inside
     the main pane width. ─────────────────────────────────────────── */
  const diffSummary = useMemo(() => {
    if (!activeCategory) return null;
    const dirtyKeys = (activeCategory.settings || []).filter((d: any) => dirty.has(d.key));
    if (dirtyKeys.length === 0) return null;
    const head = dirtyKeys.slice(0, 2);
    const rest = dirtyKeys.length - head.length;
    const parts = head.map((d: any) => {
      const next = values[d.key];
      const prev = d.value;
      const fmt = (v: unknown) =>
        typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean'
          ? String(v)
          : '…';
      return `${d.key} · ${fmt(prev)}→${fmt(next)}`;
    });
    return {
      count: dirtyKeys.length,
      caption:
        parts.join(' · ') + (rest > 0 ? ` · +${rest} more` : ''),
    };
  }, [activeCategory, dirty, values]);

  return (
    <div
      className="sunrise-frame relative"
      style={{
        width: '100%',
        height: '100%',
        background: 'var(--surface-base)',
        overflow: 'hidden',
      }}
    >
      {/* === SIDEBAR === */}
      <aside
        className="glass"
        style={{
          position: 'absolute',
          left: 12,
          top: 12,
          bottom: 76,
          width: 260,
          padding: 10,
          zIndex: 3,
          display: 'flex',
          flexDirection: 'column',
          borderRadius: 14,
        }}
      >
        {/* Phase 22-G — sidebar progress pill removed. Was a 50px-tall
            CONFIGURED block (label + count + 3px progress bar) that
            fully duplicated the 95/100 score chip already shown in the
            section header. Reclaiming the 50px lets all 10+ categories
            fit on a 600px display without sidebar scrolling — direct
            response to the operator's "деякі не видно" complaint. The
            eyebrow keeps the row but with tighter margin since the
            category list now starts immediately below it. */}
        <div className="eyebrow" style={{ marginBottom: 6 }}>
          {t('setp.eyebrow', { done: overallProgress.configured, total: overallProgress.total })}
        </div>

        {/* Category list */}
        <div
          className="no-scrollbar"
          style={{
            display: 'flex',
            flexDirection: 'column',
            gap: 1,
            flex: 1,
            overflowY: 'auto',
            minHeight: 0,
          }}
        >
          {allCategories.map((cat: any) => {
            const active = cat.id === activeCategoryId;
            const dirtyCount = (cat.settings || []).filter((d: any) =>
              dirty.has(d.key)
            ).length;
            const total = (cat.settings || []).length;            const done = total - dirtyCount;
            const pillBg = dirtyCount > 0
              ? 'var(--ph-lit-amber-a20)'
              : 'var(--ph-lit-green-bright-a18)';
            const pillFg = dirtyCount > 0 ? 'var(--ph-lit-amber-deep)' : 'var(--ph-lit-green)';
            return (
              <button
                key={cat.id}
                type="button"
                onClick={() => setActiveCategoryId(cat.id)}
                className="active:scale-[0.99]"
                style={{
                  minHeight: 44,
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                  padding: '6px 10px',
                  borderRadius: 10,
                  background: active ? 'var(--ph-lit-amber-a18)' : 'transparent',
                  borderLeft: active
                    ? '3px solid var(--ph-lit-amber)'
                    : '3px solid transparent',
                  color: active ? 'var(--ph-lit-amber-ink)' : 'var(--ink-secondary)',
                  cursor: 'pointer',
                  fontSize: 12,
                  fontWeight: active ? 600 : 500,
                  textAlign: 'left',
                }}
              >
                <span
                  aria-hidden
                  style={{
                    width: 16,
                    textAlign: 'center',
                    color: active ? 'var(--ph-lit-amber-deep)' : 'var(--ink-muted)',
                  }}
                >
                  {cat.icon}
                </span>
                <span style={{ flex: 1 }}>{cat.label}</span>
                <span
                  className="tabular"
                  style={{
                    fontSize: 9,
                    fontWeight: 700,
                    padding: '1px 6px',
                    borderRadius: 999,
                    background: pillBg,
                    color: pillFg,
                  }}
                >
                  {done}/{total}
                </span>
              </button>
            );
          })}
        </div>

        {/* Bottom — back button */}
        <button
          type="button"
          onClick={() => navigate(-1)}
          style={{
            marginTop: 8,
            minHeight: 44,
            padding: '8px 12px',
            borderRadius: 999,
            background: 'color-mix(in srgb, var(--surface-raised) 60%, transparent)',
            border: '1px solid var(--glass-border)',
            cursor: 'pointer',
            fontSize: 11,
            fontWeight: 600,
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
            justifyContent: 'center',
            color: 'var(--ink-secondary)',
          }}
        >
          <ArrowLeft size={12} strokeWidth={1.75} />
          {t('setp.backHome')}
        </button>
      </aside>

      {/* === MAIN PANE === */}
      <div
        style={{
          position: 'absolute',
          left: 284,
          right: 12,
          top: 12,
          bottom: 76,
          display: 'flex',
          flexDirection: 'column',
          zIndex: 2,
          gap: 10,
        }}
      >
        {/* Compact section header — replaces the old breadcrumb +
            ~80px Hero glass row. Surfaces section identity, score
            chip, status pill, Reset, Save in a single ~40px strip so
            the settings list gets the screen real-estate it deserves
            on a 600px-tall display. */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            padding: '2px 4px 0',
          }}
        >
          <span
            aria-hidden
            style={{
              width: 22,
              height: 22,
              borderRadius: 7,
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              background: 'linear-gradient(135deg,var(--ph-lit-amber),var(--ph-lit-orange))',
              color: 'white',
              fontSize: 12,
              fontWeight: 700,
              boxShadow: '0 2px 6px var(--ph-lit-amber-a30)',
              flexShrink: 0,
            }}
          >
            {activeCategory?.icon ?? '⚙'}
          </span>
          <span
            style={{
              fontSize: 14,
              fontWeight: 600,
              letterSpacing: '-0.01em',
              color: 'var(--ink-primary)',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
              maxWidth: 280,
            }}
          >
            {activeCategory?.label ?? '—'}
          </span>
          <span
            className="tabular"
            title={t('setp.changedOf', { done: overallProgress.configured, total: overallProgress.total })}
            style={{
              fontSize: 9,
              fontWeight: 700,
              padding: '2px 7px',
              borderRadius: 999,
              background: 'var(--ph-lit-green-bright-a16)',
              color: 'var(--ph-lit-green)',
              letterSpacing: '0.04em',
            }}
          >
            {Math.round(overallProgress.ratio * 100)}/100
          </span>
          <span style={{ flex: 1 }} />
          <StatusPill status={status} />
          <button
            type="button"
            onClick={handleReset}
            style={{
              minHeight: 44,
              padding: '6px 12px',
              borderRadius: 999,
              background: 'transparent',
              border: '1.5px solid var(--ph-lit-black-a12)',
              fontSize: 11,
              fontWeight: 600,
              cursor: 'pointer',
              color: 'var(--ink-secondary)',
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
            }}
            title={t('setp.resetSectionTitle')}
          >
            <RotateCcw size={12} strokeWidth={1.75} />
            {t('setp.reset')}
          </button>
          <button
            type="button"
            onClick={handleSave}
            disabled={
              dirtyInCategory.length === 0 || status.kind === 'saving'
            }
            style={{
              minHeight: 44,
              padding: '6px 14px',
              borderRadius: 999,
              background:
                dirtyInCategory.length > 0
                  ? 'linear-gradient(135deg,var(--ph-lit-amber),var(--ph-lit-orange))'
                  : 'var(--ph-lit-black-a4)',
              border: 'none',
              cursor: dirtyInCategory.length > 0 ? 'pointer' : 'default',
              color:
                dirtyInCategory.length > 0
                  ? 'white'
                  : 'var(--ink-muted)',
              fontSize: 11,
              fontWeight: 700,
              letterSpacing: '0.06em',
              textTransform: 'uppercase',
              opacity: dirtyInCategory.length > 0 ? 1 : 0.6,
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
              boxShadow:
                dirtyInCategory.length > 0
                  ? '0 4px 14px var(--ph-lit-amber-a40)'
                  : 'none',
            }}
          >
            {status.kind === 'saving' ? (
              <Loader2
                size={14}
                strokeWidth={1.75}
                className="animate-spin"
              />
            ) : (
              <Save size={14} strokeWidth={1.75} />
            )}
            {t('setp.saveCaps')}
            {dirtyInCategory.length > 0 && (
              <span
                style={{
                  background: 'color-mix(in srgb, var(--surface-raised) 30%, transparent)',
                  padding: '1px 6px',
                  borderRadius: 999,
                  fontSize: 9,
                }}
              >
                {dirtyInCategory.length}
              </span>
            )}
          </button>
        </div>

        {/* Settings list (scrollable inside main only). Padding tightened
            from 14 → 10 and the redundant KEY/eyebrow row removed — the
            section title is now in the compact header above and the dirty
            count is already on the SAVE button, so duplicating them here
            was pure visual weight. */}
        <div
          className="glass"
          style={{
            padding: 8,
            flex: 1,
            display: 'flex',
            flexDirection: 'column',
            gap: 6,
            overflow: 'hidden',
            minHeight: 0,
          }}
        >
          {/* Phase 22 — sticky search + Advanced gate. Search filters by
              label / description / key substring across the active
              category; the toggle persists in localStorage. */}
          <SettingsFilterBar
            query={query}
            onQuery={setQuery}
            showAdvanced={showAdvanced}
            onShowAdvanced={setShowAdvanced}
          />

          <div
            style={{
              flex: 1,
              overflowY: 'auto',
              minHeight: 0,
              display: 'flex',
              flexDirection: 'column',
              gap: 8,
              paddingRight: 4,
            }}
          >
            {!loaded && status.kind === 'loading' && (
              <div
                style={{
                  flex: 1,
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'center',
                  justifyContent: 'center',
                  gap: 10,
                }}
              >
                <Loader2
                  size={20}
                  strokeWidth={1.5}
                  className="animate-spin"
                  style={{ color: 'var(--accent)' }}
                />
                <span className="micro-label">{t('setp.loading')}</span>
              </div>
            )}

            {status.kind === 'error' && (
              <div
                style={{
                  padding: '8px 12px',
                  borderRadius: 10,
                  background:
                    'color-mix(in srgb, var(--signal-alert) 10%, transparent)',
                  border:
                    '1px solid color-mix(in srgb, var(--signal-alert) 40%, transparent)',
                  color: 'var(--signal-alert)',
                  fontFamily: 'var(--font-display)',
                  fontSize: 12,
                }}
              >
                {status.msg}
              </div>
            )}

            {/* Theme picker tiles in the Theme group ────────────────── */}
            {loaded && activeCategory && activeCategory.id === 'theme' && (
              <>
                <ThemePicker values={values} />
                <LanguagePicker />
              </>
            )}

            {loaded && activeCategory && activeCategory.id === 'about' && (
              <>
                <AboutSection />
                <div style={{ marginTop: 24 }}>
                  <BackupRestoreCard />
                </div>
              </>
            )}

            {/* Phase 19 — Mobile Companion virtual category. Fully bespoke
                pane: QR generator, countdown ring, live paired-devices list.
                Bypasses the regular settings/accordion flow because there
                are no settings keys to render here. */}
            {loaded && activeCategory && activeCategory.id === 'directory' && (
              <DirectoryConsentPanel />
            )}

            {loaded && activeCategory && activeCategory.id === 'mobile' && (
              <MobilePairing />
            )}

            {/* Phase 25-E — Personal Vault. Bespoke pane (cards grid +
                per-kind editor + reveal flow). Bypasses the regular
                settings/accordion flow because there are no settings
                keys to render. */}
            {loaded && activeCategory && activeCategory.id === 'vault' && (
              <VaultPanel />
            )}

            {/* Desktop Shell status and native features */}
            {loaded && activeCategory && activeCategory.id === 'desktop' && (
              <DesktopShellGroup />
            )}

            {/* ПОЛІС — encrypted multi-key vault, fully UI-operated. */}
            {loaded && activeCategory && activeCategory.id === 'polis_keys' && (
              <KeyVaultPanel />
            )}

            {/* Licensing — status, activation, revalidate, deactivate. */}
            {loaded && activeCategory && activeCategory.id === 'license' && (
              <LicenseGroup />
            )}

            {loaded && activeCategory && activeCategory.id === 'api_keys' && (
              <ApiKeysTab />
            )}

            {loaded && activeCategory && activeCategory.id === 'members' && (
              <MembersTab />
            )}

            {loaded &&
              activeCategory &&
              activeCategory.id !== 'about' &&
              activeCategory.id !== 'mobile' &&
              activeCategory.id !== 'vault' &&
              activeCategory.id !== 'desktop' &&
              activeCategory.id !== 'polis_keys' &&
              activeCategory.id !== 'license' &&
              activeCategory.id !== 'api_keys' &&
              activeCategory.id !== 'members' && (
              <>
                {activeCategory.id === 'ai' && <AIProviderDiagnostics />}
                {activeCategory.id === 'agent' && (
                  <>
                    <AgentLimitsGroup values={values} onChange={setValue} />
                    <AgentLayoutGroup values={values} onChange={setValue} />
                  </>
                )}
                {(activeCategory.id === 'profile' ||
                  activeCategory.id === 'personality') && (
                  <ProfileManagementSection />
                )}
                {activeCategory.settings.length === 0 && (
                  <div
                    className="playfair"
                    style={{
                      fontSize: 13,
                      color: 'var(--ink-muted)',
                      fontStyle: 'italic',
                    }}
                  >
                    No settings yet for this category.
                  </div>
                )}
                {(() => {
                  const q = query.trim().toLowerCase();
                  const visible = (activeCategory.settings || []).filter((def: any) => {
                    if (def.key === 'voice_always_on_enabled') return false;
                    // `ui_language` is registered on the backend so it
                    // round-trips through GET /settings and bootstrap can
                    // apply it — but LanguagePicker owns the control, and a
                    // generic enum row would write the key without the
                    // instant <html lang> flip.
                    if (def.key === LANGUAGE_SETTING_KEY) return false;
                    // Phase 22 — gate advanced rows behind the toggle. Search
                    // overrides the gate: if the operator types into the
                    // search box, surface every match regardless of tier.
                    if (def.tier === 'advanced' && !showAdvanced && !q) {
                      return false;
                    }
                    if (q) {
                      const haystack = `${def.label} ${def.description} ${def.key}`.toLowerCase();
                      if (!haystack.includes(q)) return false;
                    }
                    return true;
                  });
                  const groups = groupByInferredSubgroup(
                    activeCategory.id,
                    visible
                  );
                  const stateForCategory =
                    accordionState[activeCategory.id] ??
                    (() => {
                      const stored = readAccordionState(activeCategory.id);
                      if (stored) return stored;
                      const seed: Record<string, boolean> = {};
                      groups.forEach((g, idx) => {
                        seed[g.bucket.id] = idx === 0;
                      });
                      return seed;
                    })();
                  if (groups.length <= 1) {
                    return groups[0]?.items.map((def: any) => (
                      <SettingRow
                        key={def.key}
                        def={def}
                        value={values[def.key]}
                        dirty={dirty.has(def.key)}
                        onChange={(v) => setValue(def.key, v)}
                      />
                    ));
                  }
                  return groups.map((g) => {
                    const open = stateForCategory[g.bucket.id] ?? false;
                    const dirtyCount = g.items.filter((d: any) =>
                      dirty.has(d.key)
                    ).length;
                    return (
                      <SettingsAccordion
                        key={g.bucket.id}
                        id={g.bucket.id}
                        label={t(g.bucket.label)}
                        count={g.items.length}
                        dirtyCount={dirtyCount}
                        open={open}
                        onToggle={() => {
                          const nextForCategory = {
                            ...stateForCategory,
                            [g.bucket.id]: !open,
                          };
                          setAccordionState((curr) => ({
                            ...curr,
                            [activeCategory.id]: nextForCategory,
                          }));
                          writeAccordionState(
                            activeCategory.id,
                            nextForCategory
                          );
                        }}
                      >
                        {g.items.map((def: any) => (
                          <SettingRow
                            key={def.key}
                            def={def}
                            value={values[def.key]}
                            dirty={dirty.has(def.key)}
                            onChange={(v) => setValue(def.key, v)}
                          />
                        ))}
                      </SettingsAccordion>
                    );
                  });
                })()}
              </>
            )}
          </div>

          {/* Diff strip — sticky to the bottom of the glass */}
          {diffSummary && (
            <div
              style={{
                marginTop: 'auto',
                display: 'flex',
                alignItems: 'center',
                gap: 10,
                padding: '8px 10px',
                borderRadius: 10,
                background: 'var(--ph-lit-amber-a8)',
                border: '1px dashed var(--ph-lit-amber-a32)',
              }}
            >
              <GitCompareArrows
                size={14}
                strokeWidth={1.75}
                style={{ color: 'var(--ph-lit-amber-deep)' }}
              />
              <span className="micro-label" style={{ color: 'var(--ph-lit-amber-deep)' }}>
                {diffSummary.count} PENDING CHANGES
              </span>
              <span style={{ flex: 1 }} />
              <span
                className="mono"
                style={{
                  fontSize: 10,
                  color: 'var(--ink-secondary)',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                  maxWidth: 420,
                }}
                title={diffSummary.caption}
              >
                {diffSummary.caption}
              </span>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

/* ─── Phase 22 — sticky filter bar ────────────────────────────────────
 *
 * Surfaces two IA controls above the active category's settings list:
 *
 *   • Search — substring match against label, description and key
 *     (operator-friendly: types "ollama" and lands on the model + host
 *     fields no matter which category they live in).
 *   • Показати розширені — gates `tier: 'advanced'` rows behind a
 *     toggle that persists in localStorage. Search overrides the gate
 *     so an operator searching for an advanced-tier knob always finds
 *     it.
 */
function SettingsFilterBar({
  query,
  onQuery,
  showAdvanced,
  onShowAdvanced,
}: {
  query: string;
  onQuery: (v: string) => void;
  showAdvanced: boolean;
  onShowAdvanced: (v: boolean) => void;
}) {
  const { t } = useTranslation();
  // Phase 22-G — was 44px tall (8/10 padding + 28px toggle), with the
  // "Розширені" label always visible eating ~70px horizontal even when
  // off. Now ~26px tall (3/8 padding), divider gone, toggle label is
  // shown only when ON (the switch position is the affordance when off,
  // and the wrapping <label> still carries the title= for tooltips).
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        padding: '3px 8px',
        background: 'color-mix(in srgb, var(--surface-raised) 62%, transparent)',
        border: '1px solid var(--ph-lit-umber-a10)',
        borderRadius: 10,
        boxShadow: 'inset 0 1px 0 var(--ph-lit-white-a45)',
      }}
    >
      <Search
        size={13}
        strokeWidth={1.75}
        style={{ color: 'var(--ink-muted)', flexShrink: 0 }}
      />
      <input
        type="search"
        value={query}
        onChange={(e) => onQuery(e.currentTarget.value)}
        placeholder={t('setp.searchPh')}
        aria-label={t('setp.searchAria')}
        style={{
          flex: 1,
          minWidth: 0,
          background: 'transparent',
          border: 'none',
          outline: 'none',
          color: 'var(--ink-primary)',
          fontFamily: 'var(--font-display)',
          fontSize: 12,
          letterSpacing: '0.01em',
        }}
      />
      {query && (
        <button
          type="button"
          onClick={() => onQuery('')}
          aria-label={t('setp.clearSearch')}
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            width: 20,
            height: 20,
            borderRadius: 999,
            border: 'none',
            background: 'var(--ph-lit-umber-a6)',
            color: 'var(--ink-muted)',
            cursor: 'pointer',
            flexShrink: 0,
          }}
        >
          <XIcon size={11} strokeWidth={1.75} />
        </button>
      )}
      <label
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 6,
          cursor: 'pointer',
          userSelect: 'none',
        }}
        title={t('setp.advancedTitle')}
        aria-label={t('setp.advancedAria')}
      >
        {showAdvanced && (
          <span
            style={{
              fontSize: 10,
              fontWeight: 700,
              letterSpacing: '0.06em',
              textTransform: 'uppercase',
              color: 'var(--ph-lit-amber-ink)',
              fontFamily: 'var(--font-display)',
            }}
          >
            {t('setp.advancedShort')}
          </span>
        )}
        <span
          role="switch"
          aria-checked={showAdvanced}
          tabIndex={0}
          onClick={() => onShowAdvanced(!showAdvanced)}
          onKeyDown={(e) => {
            if (e.key === ' ' || e.key === 'Enter') {
              e.preventDefault();
              onShowAdvanced(!showAdvanced);
            }
          }}
          style={{
            position: 'relative',
            display: 'inline-block',
            width: 30,
            height: 16,
            borderRadius: 999,
            background: showAdvanced
              ? 'linear-gradient(135deg, var(--ph-lit-amber-a85), var(--ph-lit-orange-a85))'
              : 'color-mix(in srgb, var(--ink-muted) 35%, transparent)',
            transition: 'background 200ms ease',
            cursor: 'pointer',
            flexShrink: 0,
          }}
        >
          <span
            aria-hidden
            style={{
              position: 'absolute',
              top: 2,
              left: showAdvanced ? 16 : 2,
              width: 12,
              height: 12,
              borderRadius: 999,
              background: 'var(--ph-lit-white)',
              boxShadow: '0 2px 6px var(--ph-lit-umber-a20)',
              transition: 'left 200ms ease',
            }}
          />
        </span>
      </label>
    </div>
  );
}

/* ─── Setting row ────────────────────────────────────────────────────── */

function SettingRow({
  def,
  value,
  dirty,
  onChange,
}: {
  def: SettingDefinition;
  value: unknown;
  dirty: boolean;
  onChange: (v: unknown) => void;
}) {
  const { t } = useTranslation();
  // Phase 22-F — density pass. Was: 52px minHeight + 10/12 padding +
  // 3-row stack (label / mono key / description). With 50+ rows on
  // a 600px-tall display that ate the whole viewport. Now: 36px
  // minHeight + 5/10 padding + single-row label + mono key inlined
  // right before control + description on hover/title only. Saves
  // ~16px per row × ~50 rows = ~800px of recovered scroll surface.
  return (
    <div
      style={{
        position: 'relative',
        padding: '5px 10px',
        borderRadius: 10,
        background: 'color-mix(in srgb, var(--surface-raised) 50%, transparent)',
        border: dirty
          ? '1px solid var(--ph-lit-amber-a40)'
          : '1px solid var(--glass-border)',
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        minHeight: 36,
      }}
      title={def.description || undefined}
    >
      <Tune
        size={14}
        strokeWidth={1.75}
        style={{ color: 'var(--ph-lit-amber-deep)', flexShrink: 0 }}
      />
      <div
        style={{
          flex: 1,
          minWidth: 0,
          display: 'flex',
          alignItems: 'center',
          gap: 8,
        }}
      >
        <span
          style={{
            fontSize: 13,
            fontWeight: 600,
            color: 'var(--ink-primary)',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
            flexShrink: 1,
          }}
        >
          {def.label}
        </span>
        {/* Phase 22 — replaces the legacy " [soon]" label suffix the
            backend used to bake in. */}
        {def.unimplemented && (
          <span
            title={t('setp.soonTitle')}
            style={{
              fontSize: 8,
              padding: '1px 5px',
              borderRadius: 4,
              background: 'var(--ph-lit-slate-a20)',
              color: 'var(--ph-lit-c3e4a63)',
              fontWeight: 700,
              letterSpacing: '0.10em',
              flexShrink: 0,
            }}
          >
            {t('setp.soon')}
          </span>
        )}
        {dirty && (
          <span
            style={{
              fontSize: 8,
              padding: '1px 5px',
              borderRadius: 4,
              background: 'var(--ph-lit-amber-a22)',
              color: 'var(--ph-lit-amber-ink)',
              fontWeight: 700,
              letterSpacing: '0.10em',
              flexShrink: 0,
            }}
          >
            {t('setp.changed')}
          </span>
        )}
        {/* Показуємо пояснення, а не системний ключ: system_hostname
            нічого не каже тому, хто просто налаштовує пристрій. Ключ
            лишається в title і в пошуку. */}
        {def.description && (
          <span
            style={{
              fontSize: 10,
              color: 'var(--ink-muted)',
              marginLeft: 'auto',
              paddingLeft: 12,
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
              flexShrink: 1,
              minWidth: 0,
            }}
          >
            {def.description}
          </span>
        )}
      </div>
      <div style={{ flexShrink: 0, minWidth: 180 }}>
        <ValueEditor def={def} value={value} onChange={onChange} />
      </div>
    </div>
  );
}

/* ─── Value editor ───────────────────────────────────────────────────── */

/**
 * Phase 22 — custom editor registry. The backend stamps `editor: "Name"`
 * on `SettingDefinitionOut` for keys that warrant a bespoke widget
 * (auto-detect dropdown, chip input, host:port validator, …). The FE
 * looks the name up here and falls through to the generic
 * type-based editor when nothing matches. Keeping the registry FE-side
 * means the backend stays a thin metadata source — adding a new editor
 * is one map entry + one component, no schema migration needed.
 */
const KEY_EDITORS: Record<
  string,
  React.ComponentType<{
    value: unknown;
    onChange: (v: unknown) => void;
    def: SettingDefinition;
  }>
> = {
  OllamaModelEditor: ({ value, onChange }) => (
    <OllamaModelEditor value={value} onChange={onChange} />
  ),
  HostPortEditor: ({ value, onChange }) => (
    <HostPortEditor value={value} onChange={onChange} />
  ),
  ChipInputEditor: ({ value, onChange }) => (
    <ChipInputEditor value={value} onChange={onChange} />
  ),
};

function ValueEditor({
  def,
  value,
  onChange,
}: {
  def: SettingDefinition;
  value: unknown;
  onChange: (v: unknown) => void;
}) {
  // Prefer the backend-declared editor when present + we have a
  // matching component. Anything unknown falls through to the generic
  // editors below — keeps FE forward-compatible if the backend ever
  // ships an editor name we don't implement yet.
  if (def.editor && KEY_EDITORS[def.editor]) {
    const Editor = KEY_EDITORS[def.editor];
    return <Editor def={def} value={value} onChange={onChange} />;
  }

  if (def.type === 'boolean') {
    const on = !!value;
    // 44×44 hit area is the transparent button; the visible pill is a
    // compact 40×22 track so the switch reads as a switch, not a slab.
    return (
      <button
        type="button"
        onClick={() => onChange(!on)}
        aria-pressed={on}
        style={{
          minHeight: 44,
          minWidth: 44,
          padding: 0,
          border: 'none',
          background: 'transparent',
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          cursor: 'pointer',
        }}
      >
        <span
          aria-hidden
          style={{
            position: 'relative',
            display: 'inline-block',
            width: 40,
            height: 22,
            borderRadius: 9999,
            background: on
              ? 'linear-gradient(135deg,var(--ph-lit-amber),var(--ph-lit-orange))'
              : 'color-mix(in srgb, var(--ink-muted) 35%, transparent)',
            boxShadow: on
              ? '0 0 0 1px var(--ph-lit-amber-a50), inset 0 0 8px var(--ph-lit-white-a30)'
              : 'inset 0 0 0 1px var(--ph-lit-black-a6)',
            transition: 'background 200ms ease',
          }}
        >
          <span
            style={{
              position: 'absolute',
              top: 2,
              left: on ? 20 : 2,
              width: 18,
              height: 18,
              borderRadius: 9999,
              background: 'var(--ph-lit-white)',
              boxShadow: '0 1px 4px var(--ph-lit-black-a20)',
              transition: 'left 200ms ease',
            }}
          />
        </span>
      </button>
    );
  }

  if (def.type === 'select' && def.options) {
    return (
      <select
        value={String(value ?? '')}
        onChange={(e) => onChange(e.target.value)}
        style={{
          minHeight: 44,
          width: '100%',
          padding: '0 12px',
          borderRadius: 10,
          color: 'var(--ink-primary)',
          background: 'color-mix(in srgb, var(--surface-raised) 60%, transparent)',
          border: '1px solid var(--ph-lit-black-a6)',
          fontFamily: 'var(--font-display)',
          fontSize: 13,
          outline: 'none',
        }}
      >
        {def.options.map((opt) => (
          <option key={opt.value} value={opt.value}>
            {opt.label}
          </option>
        ))}
      </select>
    );
  }

  if (def.type === 'number') {
    return (
      <input
        type="number"
        value={Number.isFinite(value as number) ? (value as number) : 0}
        onChange={(e) => {
          const n = e.target.value === '' ? 0 : Number(e.target.value);
          onChange(Number.isFinite(n) ? n : 0);
        }}
        className="tabular"
        style={{
          minHeight: 44,
          width: '100%',
          padding: '0 12px',
          borderRadius: 10,
          color: 'var(--ink-primary)',
          background: 'color-mix(in srgb, var(--surface-raised) 60%, transparent)',
          border: '1px solid var(--ph-lit-black-a6)',
          fontFamily: 'var(--font-mono)',
          fontSize: 13,
          outline: 'none',
        }}
      />
    );
  }

  if (def.type === 'password') {
    return (
      <input
        type="password"
        placeholder="••••••••"
        value={String(value ?? '')}
        onChange={(e) => onChange(e.target.value)}
        style={{
          minHeight: 44,
          width: '100%',
          padding: '0 12px',
          borderRadius: 10,
          color: 'var(--ink-primary)',
          background: 'color-mix(in srgb, var(--surface-raised) 60%, transparent)',
          border: '1px solid var(--ph-lit-black-a6)',
          fontFamily: 'var(--font-mono)',
          fontSize: 13,
          outline: 'none',
        }}
      />
    );
  }

  return (
    <input
      type="text"
      value={String(value ?? '')}
      onChange={(e) => onChange(e.target.value)}
      style={{
        minHeight: 44,
        width: '100%',
        padding: '0 12px',
        borderRadius: 10,
        color: 'var(--ink-primary)',
        background: 'color-mix(in srgb, var(--surface-raised) 60%, transparent)',
        border: '1px solid var(--ph-lit-black-a6)',
        fontFamily: 'var(--font-mono)',
        fontSize: 13,
        outline: 'none',
      }}
    />
  );
}

/* ─── Theme picker tiles ─────────────────────────────────────────────── */

interface ThemePickerProps {
  values: Record<string, unknown>;
}

function ThemePicker({ values }: ThemePickerProps) {
  // The canonical active-theme key is `ui_theme` (THEME_SETTING_KEY) — the
  // one config.py registers, settingsBootstrap reads at first paint, and
  // settingsStore.setTheme persists. (A stale `theme_active` key used to
  // live here; it was never read back, so a picked theme silently reverted
  // on reload.) Fall back to the live <html> attribute, then sunrise-warm.
  const active: ThemeId =
    (values[THEME_SETTING_KEY] as ThemeId) ??
    ((document.documentElement.getAttribute('data-theme') as ThemeId) ||
      'sunrise-warm');

  const tiles: Array<{
    id: ThemeId;
    label: string;
    swatch: string;
    icon: React.ReactNode;
  }> = [
    {
      id: 'sunrise-warm',
      label: 'Sunrise',
      swatch:
        'linear-gradient(135deg,var(--ph-lit-cream) 0%, var(--ph-lit-amber) 50%, var(--ph-lit-orange) 100%)',
      icon: <Sun size={14} strokeWidth={2} />,
    },
    {
      id: 'amber-night',
      label: 'Amber',
      swatch:
        'linear-gradient(135deg,var(--ph-lit-c221c10) 0%, var(--ph-lit-amber-deep) 60%, var(--ph-lit-amber) 100%)',
      icon: <Moon size={14} strokeWidth={2} />,
    },
    {
      id: 'cyberdeck-cold',
      label: 'Cyberdeck',
      swatch:
        'linear-gradient(135deg,var(--ph-lit-c020617) 0%, var(--ph-lit-c0891b2) 60%, var(--ph-lit-c22d3ee) 100%)',
      icon: <Cog size={14} strokeWidth={2} />,
    },
  ];

  const handleSelect = (id: ThemeId) => {
    // setTheme is the single source of truth: it applies to the <html>
    // attribute instantly (operator sees it next frame), caches to
    // localStorage for the pre-network first paint, and persists to the
    // backend under `ui_theme` — the exact key bootstrap reads back. So the
    // choice survives reload with no Save-click, no key drift.
    void useSettingsStore.getState().setTheme(id);
  };

  // Phase 22-G — tiles 60→44, swatch 36→26, padding 8/10→4/8.
  // Was 180px for 3 tiles on a 348px scrollable list (~52%); now ~132px.
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(3, 1fr)',
        gap: 8,
      }}
    >
      {tiles.map((t) => {
        const selected = active === t.id;
        return (
          <button
            key={t.id}
            type="button"
            onClick={() => handleSelect(t.id)}
            aria-pressed={selected}
            style={{
              minHeight: 44,
              padding: '4px 8px',
              borderRadius: 10,
              background: 'color-mix(in srgb, var(--surface-raised) 55%, transparent)',
              border: selected
                ? '2px solid var(--ph-lit-amber)'
                : '1px solid var(--glass-border)',
              boxShadow: selected
                ? '0 4px 14px var(--ph-lit-amber-a30)'
                : 'var(--shadow-md)',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              textAlign: 'left',
              position: 'relative',
            }}
          >
            <div
              aria-hidden
              style={{
                width: 26,
                height: 26,
                borderRadius: 7,
                background: t.swatch,
                boxShadow: 'inset 0 0 0 1px var(--ph-lit-black-a6)',
                flexShrink: 0,
              }}
            />
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 6,
                color: 'var(--ph-lit-amber-deep)',
                flex: 1,
                minWidth: 0,
              }}
            >
              {t.icon}
              <span
                style={{
                  fontSize: 12,
                  fontWeight: 700,
                  color: 'var(--ink-primary)',
                  letterSpacing: '0.02em',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {t.label}
              </span>
            </div>
            {selected && (
              <span
                aria-hidden
                style={{
                  width: 6,
                  height: 6,
                  borderRadius: 999,
                  background: 'var(--ph-lit-amber)',
                  boxShadow: '0 0 6px var(--ph-lit-amber-a55)',
                  flexShrink: 0,
                }}
              />
            )}
          </button>
        );
      })}
    </div>
  );
}

/* ─── Language picker ────────────────────────────────────────────────── */

function LanguagePicker() {
  const { t, locale } = useTranslation();
  // Same contract as ThemePicker: the store action owns DOM + cache +
  // backend, so the choice applies instantly and survives reload without a
  // Save click. The generic `ui_language` row is filtered out above so
  // there's exactly one control writing this key.
  const handleSelect = (next: Locale) => {
    void useSettingsStore.getState().setLanguage(next);
  };

  return (
    <div style={{ marginTop: 16 }}>
      <div
        className="micro-label"
        style={{ marginBottom: 6, color: 'var(--ink-secondary)' }}
      >
        {t('settings.language.label')}
      </div>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: `repeat(${LOCALES.length}, 1fr)`,
          gap: 8,
        }}
      >
        {LOCALES.map((id) => {
          const selected = locale === id;
          return (
            <button
              key={id}
              type="button"
              onClick={() => handleSelect(id)}
              aria-pressed={selected}
              lang={id}
              style={{
                minHeight: 44,
                padding: '4px 10px',
                borderRadius: 10,
                background: 'color-mix(in srgb, var(--surface-raised) 55%, transparent)',
                border: selected
                  ? '2px solid var(--accent)'
                  : '1px solid var(--glass-border)',
                boxShadow: selected
                  ? '0 4px 14px color-mix(in srgb, var(--accent) 30%, transparent)'
                  : 'var(--shadow-md)',
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                textAlign: 'left',
              }}
            >
              <Languages
                size={14}
                strokeWidth={2}
                aria-hidden
                style={{ color: 'var(--accent)', flexShrink: 0 }}
              />
              <span
                style={{
                  fontSize: 12,
                  fontWeight: 700,
                  color: 'var(--ink-primary)',
                  letterSpacing: '0.02em',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {LOCALE_LABELS[id]}
              </span>
            </button>
          );
        })}
      </div>
      <div
        style={{
          marginTop: 6,
          fontFamily: 'var(--font-display)',
          fontSize: 11,
          color: 'var(--ink-tertiary)',
        }}
      >
        {t('settings.language.hint')}
      </div>
    </div>
  );
}

/* ─── Status pill ────────────────────────────────────────────────────── */

function StatusPill({
  status,
}: {
  status: { kind: string; msg?: string };
}) {
  if (status.kind === 'idle' || status.kind === 'loading') return null;
  const color =
    status.kind === 'saved'
      ? 'var(--signal-ok)'
      : status.kind === 'error'
        ? 'var(--signal-alert)'
        : 'var(--accent)';
  const label =
    status.kind === 'saving'
      ? 'Saving…'
      : status.kind === 'saved'
        ? 'Saved'
        : status.kind === 'error'
          ? 'Error'
          : '';
  return (
    <span
      className="tabular"
      style={{
        padding: '4px 10px',
        borderRadius: 999,
        background: `color-mix(in srgb, ${color} 14%, transparent)`,
        color,
        border: `1px solid ${color}`,
        fontFamily: 'var(--font-display)',
        fontSize: 10,
        letterSpacing: '0.14em',
        textTransform: 'uppercase',
      }}
    >
      {label}
    </span>
  );
}

/* ─── AI diagnostics ─────────────────────────────────────────────────── */

function AIProviderDiagnostics() {
  const [state, setState] = useState<{
    running: null | 'ollama' | 'gemini' | 'reset';
    result: Record<'ollama' | 'gemini', AITestResponse | null>;
  }>({ running: null, result: { ollama: null, gemini: null } });

  const run = useCallback(async (provider: 'ollama' | 'gemini') => {
    setState((s) => ({ ...s, running: provider }));
    try {
      const res = await aiApi.test(provider);
      setState((s) => ({
        running: null,
        result: { ...s.result, [provider]: res },
      }));
    } catch (err) {
      setState((s) => ({
        running: null,
        result: {
          ...s.result,
          [provider]: {
            ok: false,
            provider,
            latency_ms: 0,
            error: err instanceof Error ? err.message : 'Request failed',
          },
        },
      }));
    }
  }, []);

  const runReset = useCallback(async () => {
    setState((s) => ({ ...s, running: 'reset' }));
    try {
      await aiApi.reset();
      // Clear results to encourage re-testing
      setState({ running: null, result: { ollama: null, gemini: null } });
    } catch (err) {
      setState((s) => ({ ...s, running: null }));
    }
  }, []);

  // Phase 22-F — was a column-stacked sub-glass: eyebrow row + test
  // button row, padding 12/14, gap 10. Now a single-row pill — eyebrow
  // inlined with the buttons. Saves ~30px.
  return (
    <div
      className="sub-glass"
      style={{
        padding: '6px 10px',
        display: 'flex',
        alignItems: 'center',
        flexWrap: 'wrap',
        gap: 8,
        borderRadius: 12,
      }}
    >
      <div className="eyebrow-amber" style={{ marginRight: 4 }}>
        Connectivity
      </div>
      <ProviderTestButton
        label="Test Ollama"
        onClick={() => run('ollama')}
        busy={state.running === 'ollama'}
        result={state.result.ollama}
      />
      <ProviderTestButton
        label="Test Gemini"
        onClick={() => run('gemini')}
        busy={state.running === 'gemini'}
        result={state.result.gemini}
      />
      <button
        type="button"
        onClick={runReset}
        disabled={state.running === 'reset'}
        style={{
          minHeight: 44,
          padding: '0 14px',
          background: 'var(--ph-lit-black-a5)',
          color: 'var(--ink-muted)',
          border: '1px solid var(--ph-lit-black-a10)',
          borderRadius: 9999,
          fontFamily: 'var(--font-display)',
          fontSize: 10,
          letterSpacing: '0.05em',
          textTransform: 'uppercase',
          opacity: state.running === 'reset' ? 0.6 : 1,
          cursor: state.running === 'reset' ? 'default' : 'pointer',
          display: 'inline-flex',
          alignItems: 'center',
          gap: 6,
          marginLeft: 'auto',
        }}
      >
        {state.running === 'reset' ? (
          <Loader2 size={12} className="animate-spin" />
        ) : (
          <RotateCcw size={12} />
        )}
        Force Reset AI
      </button>
    </div>
  );
}

function ProviderTestButton({
  label,
  onClick,
  busy,
  result,
}: {
  label: string;
  onClick: () => void;
  busy: boolean;
  result: AITestResponse | null;
}) {
  const tone = result == null ? 'idle' : result.ok ? 'ok' : 'err';
  const color =
    tone === 'ok'
      ? 'var(--signal-ok)'
      : tone === 'err'
        ? 'var(--signal-alert)'
        : 'var(--accent)';
  const summary = result
    ? result.ok
      ? `Connected · ${result.latency_ms}ms`
      : `Failed: ${result.error ?? 'unknown'}`
    : 'Not tested';

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <button
        type="button"
        onClick={onClick}
        disabled={busy}
        style={{
          minHeight: 44,
          padding: '0 14px',
          background: `color-mix(in srgb, ${color} 14%, transparent)`,
          color,
          border: `1px solid color-mix(in srgb, ${color} 50%, transparent)`,
          borderRadius: 9999,
          fontFamily: 'var(--font-display)',
          fontSize: 12,
          letterSpacing: '0.05em',
          opacity: busy ? 0.6 : 1,
          cursor: busy ? 'default' : 'pointer',
          display: 'inline-flex',
          alignItems: 'center',
          gap: 6,
        }}
        aria-label={label}
      >
        {busy ? (
          <Loader2 size={14} strokeWidth={1.75} className="animate-spin" />
        ) : tone === 'ok' ? (
          <CheckCircle2 size={14} strokeWidth={1.75} />
        ) : tone === 'err' ? (
          <AlertTriangle size={14} strokeWidth={1.75} />
        ) : (
          <Plug size={14} strokeWidth={1.75} />
        )}
        {label}
      </button>
      <span
        className="mono"
        style={{
          fontSize: 11,
          color: 'var(--ink-muted)',
          maxWidth: 340,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
        }}
        title={result?.error ?? summary}
      >
        {summary}
      </span>
    </div>
  );
}

function OllamaModelEditor({
  value,
  onChange,
}: {
  value: unknown;
  onChange: (v: unknown) => void;
}) {
  const [models, setModels] = useState<OllamaModelInfo[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [offlineMessage, setOfflineMessage] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    aiApi
      .listModels()
      .then((res) => {
        if (cancelled) return;
        if (res.ok) {
          setModels(res.models);
          setOfflineMessage(null);
        } else {
          setModels([]);
          setOfflineMessage('Ollama offline, enter manually');
        }
      })
      .catch(() => {
        if (cancelled) return;
        setModels([]);
        setOfflineMessage('Ollama unreachable, enter manually');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const current = String(value ?? '');
  const showDropdown = models != null && models.length > 0;

  if (loading) {
    return (
      <div
        style={{
          minHeight: 44,
          padding: '0 12px',
          borderRadius: 10,
          background: 'color-mix(in srgb, var(--surface-raised) 60%, transparent)',
          border: '1px solid var(--ph-lit-black-a6)',
          color: 'var(--ink-muted)',
          fontFamily: 'var(--font-mono)',
          fontSize: 13,
          display: 'inline-flex',
          alignItems: 'center',
          gap: 6,
        }}
      >
        <Loader2 size={12} strokeWidth={1.75} className="animate-spin" />
        Loading models…
      </div>
    );
  }

  if (!showDropdown) {
    return (
      <div
        style={{ display: 'flex', flexDirection: 'column', gap: 4 }}
      >
        <input
          type="text"
          value={current}
          onChange={(e) => onChange(e.target.value)}
          placeholder="e.g. llama3.2:3b"
          style={{
            minHeight: 44,
            width: '100%',
            padding: '0 12px',
            borderRadius: 10,
            color: 'var(--ink-primary)',
            background: 'color-mix(in srgb, var(--surface-raised) 60%, transparent)',
            border: '1px solid var(--ph-lit-black-a6)',
            fontFamily: 'var(--font-mono)',
            fontSize: 13,
            outline: 'none',
          }}
        />
        <span
          style={{
            fontFamily: 'var(--font-display)',
            fontSize: 10,
            color: 'var(--signal-warn)',
            letterSpacing: '0.05em',
          }}
        >
          {offlineMessage ?? 'No models installed — run `ollama pull <name>`'}
        </span>
      </div>
    );
  }

  const hasCurrent = models!.some((m) => m.name === current);
  return (
    <select
      value={current}
      onChange={(e) => onChange(e.target.value)}
      style={{
        minHeight: 44,
        width: '100%',
        padding: '0 12px',
        borderRadius: 10,
        color: 'var(--ink-primary)',
        background: 'color-mix(in srgb, var(--surface-raised) 60%, transparent)',
        border: '1px solid var(--ph-lit-black-a6)',
        fontFamily: 'var(--font-mono)',
        fontSize: 13,
        outline: 'none',
      }}
    >
      {!hasCurrent && current && (
        <option value={current}>{current} (not installed)</option>
      )}
      {models!.map((m) => (
        <option key={m.name} value={m.name}>
          {m.name}
          {m.parameter_size ? ` · ${m.parameter_size}` : ''}
          {m.quantization ? ` · ${m.quantization}` : ''}
        </option>
      ))}
    </select>
  );
}

/* ─── Phase 22 — host:port editor ───────────────────────────────────────
 *
 * Used for `ai_ollama_host` (and any future `host:port` knob). Validates
 * the URL/host format inline so the operator gets feedback before save
 * — bad value paints the border coral and surfaces a hint underneath.
 * Persists the raw string value (no normalization) so the operator
 * remains in control of trailing slashes, scheme, and port literals.
 */
function HostPortEditor({
  value,
  onChange,
}: {
  value: unknown;
  onChange: (v: unknown) => void;
}) {
  const { t } = useTranslation();
  const current = String(value ?? '');
  // Accept either a bare host[:port] or a full http(s) URL. We don't
  // ping the host — that's the AI provider's job at startup. This is
  // pure shape validation so typos surface before save.
  const ok = useMemo(() => {
    const trimmed = current.trim();
    if (trimmed.length === 0) return true; // empty is "use default"
    if (/^https?:\/\/[^\s/]+(?:\/.*)?$/i.test(trimmed)) return true;
    if (/^[A-Za-z0-9_.-]+(?::\d{1,5})?$/.test(trimmed)) return true;
    return false;
  }, [current]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <input
        type="text"
        value={current}
        onChange={(e) => onChange(e.target.value)}
        placeholder="http://localhost:11434"
        spellCheck={false}
        style={{
          minHeight: 44,
          width: '100%',
          padding: '0 12px',
          borderRadius: 10,
          color: 'var(--ink-primary)',
          background: 'color-mix(in srgb, var(--surface-raised) 60%, transparent)',
          border: ok
            ? '1px solid var(--ph-lit-black-a6)'
            : '1px solid var(--ph-lit-coral-a55)',
          fontFamily: 'var(--font-mono)',
          fontSize: 13,
          outline: 'none',
        }}
      />
      {!ok && (
        <span
          style={{
            fontFamily: 'var(--font-display)',
            fontSize: 10,
            color: 'var(--signal-warn, var(--ph-lit-cb85c00))',
            letterSpacing: '0.05em',
          }}
        >
          {t('setp.hostPortHint')}
        </span>
      )}
    </div>
  );
}

/* ─── Phase 22 — chip-input editor ──────────────────────────────────────
 *
 * Used for `list[str]` settings (e.g. `security_trusted_proxies`). The
 * backend returns the value as a JSON array; the FE renders each item
 * as a removable chip and surfaces a single text input that turns
 * comma- or Enter-terminated tokens into new chips. Persists the value
 * as `string[]` so the backend Pydantic coercion just works.
 */
function ChipInputEditor({
  value,
  onChange,
}: {
  value: unknown;
  onChange: (v: unknown) => void;
}) {
  const { t } = useTranslation();
  // Tolerate legacy string blobs ("a,b,c") + the canonical list form.
  // Settings imported from older builds may still arrive as strings —
  // normalize on render so the operator never sees a stringified array.
  const items = useMemo<string[]>(() => {
    if (Array.isArray(value)) {
      return value.map((v) => String(v).trim()).filter(Boolean);
    }
    if (typeof value === 'string') {
      return value
        .split(/[,\n]/)
        .map((s) => s.trim())
        .filter(Boolean);
    }
    return [];
  }, [value]);

  const [draft, setDraft] = useState('');

  const commit = (next: string[]) => onChange(next);

  const addToken = (raw: string) => {
    const token = raw.trim();
    if (!token) return;
    if (items.includes(token)) {
      setDraft('');
      return;
    }
    commit([...items, token]);
    setDraft('');
  };

  const removeAt = (idx: number) => {
    const next = items.slice();
    next.splice(idx, 1);
    commit(next);
  };

  return (
    <div
      style={{
        display: 'flex',
        flexWrap: 'wrap',
        gap: 6,
        alignItems: 'center',
        minHeight: 44,
        width: '100%',
        padding: '6px 8px',
        borderRadius: 10,
        background: 'color-mix(in srgb, var(--surface-raised) 60%, transparent)',
        border: '1px solid var(--ph-lit-black-a6)',
      }}
    >
      {items.map((token, idx) => (
        <span
          key={`${token}-${idx}`}
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 4,
            padding: '2px 4px 2px 8px',
            borderRadius: 999,
            background: 'var(--ph-lit-amber-a18)',
            color: 'var(--ph-lit-amber-ink)',
            fontFamily: 'var(--font-mono)',
            fontSize: 11,
            letterSpacing: '0.02em',
          }}
        >
          {token}
          <button
            type="button"
            onClick={() => removeAt(idx)}
            aria-label={t('setp.removeToken', { token })}
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              width: 18,
              height: 18,
              borderRadius: 999,
              border: 'none',
              background: 'var(--ph-lit-umber-a10)',
              color: 'var(--ph-lit-amber-ink)',
              cursor: 'pointer',
              padding: 0,
            }}
          >
            <XIcon size={10} strokeWidth={2} />
          </button>
        </span>
      ))}
      <input
        type="text"
        value={draft}
        onChange={(e) => {
          const v = e.currentTarget.value;
          // Comma or whitespace acts as a commit terminator — the chip
          // appears immediately so the operator sees the boundary.
          if (/[,\s]/.test(v)) {
            const parts = v.split(/[,\s]+/).filter(Boolean);
            for (const p of parts) addToken(p);
            return;
          }
          setDraft(v);
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault();
            addToken(draft);
          } else if (e.key === 'Backspace' && draft === '' && items.length > 0) {
            removeAt(items.length - 1);
          }
        }}
        onBlur={() => addToken(draft)}
        placeholder={items.length === 0 ? t('setp.addEntry') : ''}
        spellCheck={false}
        style={{
          flex: '1 1 80px',
          minWidth: 80,
          background: 'transparent',
          border: 'none',
          outline: 'none',
          color: 'var(--ink-primary)',
          fontFamily: 'var(--font-mono)',
          fontSize: 12,
          padding: '4px 2px',
        }}
      />
    </div>
  );
}

/* ─── About ──────────────────────────────────────────────────────────── */

function AboutSection() {
  return (
    <div
      className="sub-glass"
      style={{
        borderRadius: 12,
        padding: '10px 12px',
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'baseline',
          gap: 8,
        }}
      >
        <span
          style={{
            fontSize: 14,
            fontWeight: 700,
            color: 'var(--ink-primary)',
            letterSpacing: '-0.01em',
          }}
        >
          Nightpost
        </span>
        <span
          className="tabular"
          style={{
            fontSize: 11,
            fontWeight: 700,
            padding: '1px 7px',
            borderRadius: 999,
            background: 'var(--ph-lit-amber-a18)',
            color: 'var(--ph-lit-amber-ink)',
            letterSpacing: '0.04em',
          }}
        >
          0.6 · PHASE 06
        </span>
        <span style={{ flex: 1 }} />
        <span
          className="micro-label"
          style={{ color: 'var(--ink-muted)' }}
        >
          PC + PHONE
        </span>
      </div>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: '70px 1fr',
          rowGap: 3,
          columnGap: 10,
          fontFamily: 'var(--font-mono)',
          fontSize: 11,
          color: 'var(--ink-muted)',
        }}
      >
        <span>Frontend</span>
        <span style={{ color: 'var(--ink-primary)' }}>
          React 18 · Vite 5 · Tailwind 3
        </span>
        <span>Backend</span>
        <span style={{ color: 'var(--ink-primary)' }}>
          FastAPI · SQLite · ChromaDB
        </span>
        <span>AI</span>
        <span style={{ color: 'var(--ink-primary)' }}>
          Cloud LLM · optional Ollama fallback
        </span>
        <span>Voice</span>
        <span style={{ color: 'var(--ink-primary)' }}>
          Whisper / Vosk STT · Piper TTS
        </span>
      </div>
    </div>
  );
}
