/**
 * Phase 25-E — Vault Panel.
 *
 * Bespoke pane for the "Сховище" settings category. Renders the user's
 * encrypted personal cards as glass tiles (320×180), provides:
 *   • search + kind filter + tag filter
 *   • per-kind editor modal with secret eye-toggle reveal flow
 *   • include-deleted toggle + restore action
 *   • AI-touched marker (last_accessed_at fresh → glow)
 *   • audit timeline drawer per card
 *
 * Talks to /api/v1/vault/* via vaultApi (services/api.ts).
 *
 * Field-config map is the source of truth for what each `kind` of card
 * looks like — adding a new kind means adding one entry here. The
 * editor is generic; the map decides which fields to show, their
 * labels, default secret flag, placeholder text.
 */
import { useEffect, useMemo, useState } from 'react';
import {
  vaultApi,
  type VaultCard,
  type VaultCardKind,
  type VaultFieldInput,
} from '../../services/api';
import { useTranslation } from '../../i18n/useTranslation';
import { tNow, tPlural } from '../chat/tNow';

// ─── Field config per kind ──────────────────────────────────────────────────


interface FieldConfig {
  key: string;
  label: string;
  secret: boolean;
  placeholder?: string;
  multiline?: boolean;
}


interface KindConfig {
  id: VaultCardKind;
  label: string;
  icon: string;
  fields: FieldConfig[];
}


const kindConfigs = (): KindConfig[] => [
  {
    id: 'email_account', label: tNow('vault.kind.email'), icon: '✉',
    fields: [
      { key: 'provider', label: tNow('vault.f.provider'), secret: false, placeholder: 'Gmail, Proton, Outlook…' },
      { key: 'address', label: tNow('vault.f.address'), secret: false, placeholder: 'name@example.com' },
      { key: 'password', label: tNow('vault.f.password'), secret: true },
      { key: 'app_password', label: 'App password', secret: true },
      { key: 'recovery', label: 'Recovery code', secret: true, multiline: true },
    ],
  },
  {
    id: 'service_login', label: tNow('vault.kind.serviceLogin'), icon: '🔑',
    fields: [
      { key: 'service', label: tNow('vault.f.service'), secret: false },
      { key: 'url', label: 'URL', secret: false, placeholder: 'https://…' },
      { key: 'username', label: 'Username', secret: false },
      { key: 'password', label: tNow('vault.f.password'), secret: true },
      { key: 'totp_secret', label: 'TOTP secret', secret: true },
    ],
  },
  {
    id: 'messenger', label: tNow('vault.kind.messenger'), icon: '💬',
    fields: [
      { key: 'platform', label: tNow('vault.f.platform'), secret: false, placeholder: 'Telegram, Signal, Viber' },
      { key: 'handle', label: 'Handle', secret: false, placeholder: '@nickname' },
      { key: 'password', label: tNow('vault.f.password'), secret: true },
      { key: 'recovery_phone', label: 'Recovery phone', secret: false },
    ],
  },
  {
    id: 'phone', label: tNow('vault.f.phone'), icon: '📱',
    fields: [
      { key: 'owner', label: tNow('vault.f.owner'), secret: false },
      { key: 'number', label: tNow('vault.f.number'), secret: false, placeholder: '+380…' },
      { key: 'carrier', label: tNow('vault.f.carrier'), secret: false },
      { key: 'notes', label: tNow('vault.f.notes'), secret: false, multiline: true },
    ],
  },
  {
    id: 'company', label: tNow('vault.kind.company'), icon: '🏢',
    fields: [
      { key: 'legal_name', label: tNow('vault.f.legalName'), secret: false },
      { key: 'edrpou', label: tNow('vault.f.edrpou'), secret: false },
      { key: 'iban', label: 'IBAN', secret: true },
      { key: 'address', label: tNow('vault.f.address'), secret: false, multiline: true },
      { key: 'contact_person', label: tNow('vault.f.contactPerson'), secret: false },
    ],
  },
  {
    id: 'payment_method', label: tNow('vault.kind.payment'), icon: '💳',
    fields: [
      { key: 'brand', label: tNow('vault.f.brand'), secret: false, placeholder: 'Visa, MasterCard' },
      { key: 'last4', label: tNow('vault.f.last4'), secret: false, placeholder: '1234' },
      { key: 'expiry', label: tNow('vault.f.validUntilCard'), secret: false, placeholder: 'MM/YY' },
      { key: 'cvv', label: 'CVV/CVC', secret: true },
      { key: 'pan', label: tNow('vault.f.pan'), secret: true },
      { key: 'billing_address', label: tNow('vault.f.billingAddress'), secret: false, multiline: true },
    ],
  },
  {
    id: 'api_key', label: tNow('vault.kind.apiKey'), icon: '🔌',
    fields: [
      { key: 'service', label: tNow('vault.f.service'), secret: false, placeholder: 'OpenAI, Stripe…' },
      { key: 'key', label: tNow('vault.f.key'), secret: true },
      { key: 'scopes', label: 'Scope', secret: false },
      { key: 'expires_at', label: tNow('vault.f.validUntil'), secret: false, placeholder: 'YYYY-MM-DD' },
    ],
  },
  {
    id: 'document', label: tNow('vault.kind.document'), icon: '📄',
    fields: [
      { key: 'kind', label: tNow('vault.f.type'), secret: false, placeholder: tNow('vault.f.typePlaceholder') },
      { key: 'number', label: tNow('vault.f.number'), secret: true },
      { key: 'issued_at', label: tNow('vault.f.issued'), secret: false, placeholder: 'YYYY-MM-DD' },
      { key: 'expires_at', label: tNow('vault.f.validUntil'), secret: false, placeholder: 'YYYY-MM-DD' },
      { key: 'scan_path', label: tNow('vault.f.scanPath'), secret: false },
    ],
  },
  {
    id: 'contact', label: tNow('vault.kind.contact'), icon: '👤',
    fields: [
      { key: 'full_name', label: tNow('vault.f.fullName'), secret: false },
      { key: 'role', label: tNow('vault.f.role'), secret: false },
      { key: 'phone', label: tNow('vault.f.phone'), secret: false },
      { key: 'email', label: 'Email', secret: false },
      { key: 'telegram', label: 'Telegram', secret: false },
      { key: 'notes', label: tNow('vault.f.notes'), secret: false, multiline: true },
    ],
  },
  {
    id: 'wifi_network', label: tNow('vault.kind.wifi'), icon: '📶',
    fields: [
      { key: 'ssid', label: 'SSID', secret: false },
      { key: 'password', label: tNow('vault.f.password'), secret: true },
      { key: 'bssid', label: 'BSSID', secret: false },
      { key: 'location', label: tNow('vault.f.location'), secret: false },
    ],
  },
  {
    id: 'crypto_wallet', label: tNow('vault.kind.crypto'), icon: '🪙',
    fields: [
      { key: 'chain', label: tNow('vault.f.chain'), secret: false, placeholder: 'BTC, ETH, SOL…' },
      { key: 'address', label: tNow('vault.f.address'), secret: false },
      { key: 'seed_phrase', label: tNow('vault.f.seed'), secret: true, multiline: true },
      { key: 'private_key', label: 'Private key', secret: true },
    ],
  },
  {
    id: 'custom', label: tNow('vault.kind.custom'), icon: '🗂',
    fields: [
      { key: 'value', label: tNow('vault.f.value'), secret: false, multiline: true },
      { key: 'secret_value', label: tNow('vault.f.secret'), secret: true, multiline: true },
    ],
  },
];


function configFor(kind: VaultCardKind): KindConfig {
  const all = kindConfigs();
  return all.find((c) => c.id === kind) ?? all[all.length - 1];
}


// ─── Card tile ──────────────────────────────────────────────────────────────


interface CardTileProps {
  card: VaultCard;
  onOpen: () => void;
}


function CardTile({ card, onOpen }: CardTileProps) {
  const { t } = useTranslation();
  const cfg = configFor(card.kind);
  const aiTouchedRecently =
    card.last_accessed_at !== null
    && Date.now() - new Date(card.last_accessed_at).getTime() < 5 * 60 * 1000;
  const isDeleted = card.deleted_at !== null;
  const secretCount = Object.values(card.field_kinds).filter((k) => k === 'secret').length;
  const plainCount = Object.values(card.field_kinds).filter((k) => k === 'plain').length;
  return (
    <button
      type="button"
      onClick={onOpen}
      className={[
        'group relative w-[320px] h-[180px] text-left',
        'rounded-2xl backdrop-blur-md',
        'transition-all duration-200 hover:scale-[1.02]',
        'border border-white/15 bg-white/8',
        isDeleted ? 'opacity-50 grayscale' : '',
        aiTouchedRecently ? 'ring-2 ring-cyan-400/60 shadow-cyan-500/30 shadow-lg' : '',
      ].join(' ')}
      aria-label={t('vault.openCard', { label: card.label })}
    >
      <div className="absolute inset-0 p-4 flex flex-col">
        <div className="flex items-start justify-between gap-2">
          <div className="text-3xl leading-none">{cfg.icon}</div>
          <div className="flex flex-col items-end gap-1 text-[10px] uppercase tracking-wider text-white/50">
            <span>{cfg.label}</span>
            {!card.ai_writable && (
              <span className="px-1.5 py-0.5 rounded bg-amber-400/20 text-amber-200">
                AI-RO
              </span>
            )}
          </div>
        </div>
        <div className="mt-2 text-base font-semibold text-white/90 line-clamp-2">
          {card.label}
        </div>
        {card.tags.length > 0 && (
          <div className="mt-1 flex gap-1 flex-wrap">
            {card.tags.slice(0, 4).map((t) => (
              <span
                key={t}
                className="px-1.5 py-0.5 text-[10px] rounded bg-white/10 text-white/60"
              >
                #{t}
              </span>
            ))}
          </div>
        )}
        <div className="mt-auto flex items-center justify-between text-[11px] text-white/40">
          <span>
            {plainCount} plain · {secretCount} 🔒
          </span>
          {aiTouchedRecently && <span className="text-cyan-300">AI ↑</span>}
          {isDeleted && <span className="text-rose-300">deleted</span>}
        </div>
      </div>
    </button>
  );
}


// ─── Editor modal ───────────────────────────────────────────────────────────


type FieldDraft = Record<string, { value: string; secret: boolean; revealed?: string }>;


interface EditorProps {
  card: VaultCard | null;
  draftKind: VaultCardKind;
  onClose: () => void;
  onSaved: () => void;
}


function buildInitialDraft(card: VaultCard | null, kind: VaultCardKind): FieldDraft {
  const cfg = configFor(kind);
  const draft: FieldDraft = {};
  for (const f of cfg.fields) {
    if (card && f.key in card.fields) {
      const isSecret = card.field_kinds[f.key] === 'secret';
      draft[f.key] = {
        value: isSecret ? '' : card.fields[f.key],
        secret: isSecret,
      };
    } else {
      draft[f.key] = { value: '', secret: f.secret };
    }
  }
  return draft;
}


function CardEditor({ card, draftKind, onClose, onSaved }: EditorProps) {
  const { t } = useTranslation();
  const isCreate = card === null;
  const cfg = configFor(draftKind);
  const [label, setLabel] = useState<string>(card?.label ?? '');
  const [tags, setTags] = useState<string>((card?.tags ?? []).join(', '));
  const [aiWritable, setAiWritable] = useState<boolean>(card?.ai_writable ?? true);
  const [draft, setDraft] = useState<FieldDraft>(() => buildInitialDraft(card, draftKind));
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [revealJustifications, setRevealJustifications] = useState<Record<string, string>>({});

  async function handleReveal(fieldKey: string) {
    if (!card) return;
    const justification =
      revealJustifications[fieldKey]
      || `Operator opened "${card.label}" → ${fieldKey} from Settings`;
    setBusy(true);
    setErr(null);
    try {
      const res = await vaultApi.reveal(card.id, fieldKey, justification);
      setDraft((prev) => ({
        ...prev,
        [fieldKey]: { ...prev[fieldKey], revealed: res.value },
      }));
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function handleHide(fieldKey: string) {
    setDraft((prev) => ({
      ...prev,
      [fieldKey]: { ...prev[fieldKey], revealed: undefined },
    }));
  }

  async function handleSave() {
    if (label.trim().length < 1) {
      setErr(t('vault.labelEmpty'));
      return;
    }
    setBusy(true);
    setErr(null);
    const fields: Record<string, VaultFieldInput> = {};
    for (const f of cfg.fields) {
      const d = draft[f.key];
      if (!d) continue;
      // For SECRET fields: only send if user typed a new value (so we don't
      // overwrite the existing ciphertext with an empty string when editing).
      if (d.secret) {
        if (d.value.trim() !== '') {
          fields[f.key] = { value: d.value, secret: true };
        }
      } else {
        // Plain field — always send (even empty, so user can clear it).
        fields[f.key] = { value: d.value, secret: false };
      }
    }
    const tagList = tags
      .split(',')
      .map((t) => t.trim())
      .filter((t) => t.length > 0);
    try {
      if (isCreate) {
        await vaultApi.create({
          kind: draftKind,
          label: label.trim(),
          fields,
          tags: tagList,
          ai_writable: aiWritable,
        });
      } else {
        await vaultApi.patch(card!.id, {
          label: label.trim() === card!.label ? undefined : label.trim(),
          fields: Object.keys(fields).length ? fields : undefined,
          tags: tagList.join(',') === (card!.tags ?? []).join(',') ? undefined : tagList,
          ai_writable: aiWritable === card!.ai_writable ? undefined : aiWritable,
        });
      }
      onSaved();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete() {
    if (!card) return;
    if (!confirm(t('vault.deleteConfirm', { label: card.label }))) return;
    setBusy(true);
    setErr(null);
    try {
      await vaultApi.remove(card.id);
      onSaved();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-6"
      onClick={onClose}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-xl max-h-[90vh] overflow-y-auto rounded-3xl border border-white/15 bg-zinc-950/95 p-6"
      >
        <div className="flex items-start justify-between gap-3 mb-4">
          <div className="flex items-center gap-3">
            <div className="text-4xl">{cfg.icon}</div>
            <div>
              <div className="text-lg font-semibold text-white/95">
                {isCreate ? t('vault.newCard') : t('vault.editing')}
              </div>
              <div className="text-xs text-white/50">{cfg.label}</div>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="text-white/60 hover:text-white text-xl leading-none"
            aria-label={t('vault.close')}
          >
            ×
          </button>
        </div>

        <label className="block mb-3">
          <div className="text-xs text-white/60 mb-1">{t('vault.cardName')}</div>
          <input
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder={t('vault.cardNamePh')}
            maxLength={160}
            className="w-full h-11 px-3 rounded-lg bg-white/5 border border-white/10 text-white/95 focus:border-cyan-400/60 outline-none"
          />
        </label>

        <label className="block mb-4">
          <div className="text-xs text-white/60 mb-1">{t('vault.tags')}</div>
          <input
            value={tags}
            onChange={(e) => setTags(e.target.value)}
            placeholder="mail, primary, work"
            className="w-full h-11 px-3 rounded-lg bg-white/5 border border-white/10 text-white/95 focus:border-cyan-400/60 outline-none"
          />
        </label>

        <div className="flex items-center justify-between mb-4 px-3 py-2 rounded-lg bg-white/5">
          <div>
            <div className="text-sm text-white/80">{t('vault.aiEdit')}</div>
            <div className="text-[11px] text-white/40">
              {t('vault.aiEditHint')}
            </div>
          </div>
          <button
            type="button"
            onClick={() => setAiWritable((v) => !v)}
            className={`w-11 h-6 rounded-full transition-all ${
              aiWritable ? 'bg-cyan-400/80' : 'bg-white/15'
            }`}
            aria-pressed={aiWritable}
          >
            <span
              className={`block w-5 h-5 rounded-full bg-white transition-all ${
                aiWritable ? 'ml-5' : 'ml-1'
              }`}
            />
          </button>
        </div>

        <div className="space-y-3">
          {cfg.fields.map((f) => {
            const d = draft[f.key];
            if (!d) return null;
            const isRevealed = d.secret && d.revealed !== undefined;
            return (
              <div key={f.key} className="rounded-lg bg-white/5 border border-white/10 p-3">
                <div className="flex items-center justify-between mb-1.5">
                  <div className="text-xs text-white/70">
                    {f.label}
                    {f.secret && (
                      <span className="ml-2 text-[10px] uppercase tracking-wider text-amber-300/80">
                        secret
                      </span>
                    )}
                  </div>
                  {!isCreate && f.secret && (
                    <button
                      type="button"
                      onClick={() => (isRevealed ? handleHide(f.key) : handleReveal(f.key))}
                      disabled={busy}
                      className="text-xs text-cyan-300 hover:text-cyan-200"
                    >
                      {isRevealed ? t('vault.hide') : t('vault.show')}
                    </button>
                  )}
                </div>
                {f.multiline ? (
                  <textarea
                    value={isRevealed ? d.revealed! : d.value}
                    onChange={(e) =>
                      setDraft((prev) => ({
                        ...prev,
                        [f.key]: { ...prev[f.key], value: e.target.value },
                      }))
                    }
                    placeholder={f.placeholder}
                    rows={3}
                    readOnly={isRevealed}
                    className="w-full px-2 py-1.5 rounded bg-black/40 border border-white/10 text-white/95 font-mono text-sm outline-none"
                  />
                ) : (
                  <input
                    type={f.secret && !isRevealed ? 'password' : 'text'}
                    value={isRevealed ? d.revealed! : d.value}
                    onChange={(e) =>
                      setDraft((prev) => ({
                        ...prev,
                        [f.key]: { ...prev[f.key], value: e.target.value },
                      }))
                    }
                    placeholder={
                      f.secret && !isCreate ? t('vault.secretKeep') : f.placeholder
                    }
                    readOnly={isRevealed}
                    className="w-full h-10 px-3 rounded bg-black/40 border border-white/10 text-white/95 font-mono text-sm outline-none"
                  />
                )}
                {f.secret && !isCreate && (
                  <input
                    value={revealJustifications[f.key] || ''}
                    onChange={(e) =>
                      setRevealJustifications((prev) => ({
                        ...prev,
                        [f.key]: e.target.value,
                      }))
                    }
                    placeholder={t('vault.revealReason')}
                    className="mt-2 w-full h-8 px-2 rounded bg-white/5 border border-white/5 text-white/60 text-xs outline-none"
                  />
                )}
              </div>
            );
          })}
        </div>

        {err && (
          <div className="mt-4 p-2 rounded bg-rose-500/15 border border-rose-400/30 text-rose-200 text-sm">
            {err}
          </div>
        )}

        <div className="mt-6 flex gap-3 justify-between">
          {!isCreate && (
            <button
              type="button"
              onClick={handleDelete}
              disabled={busy}
              className="h-11 px-4 rounded-lg bg-rose-500/15 border border-rose-400/30 text-rose-200 hover:bg-rose-500/25"
            >
              {t('vault.delete')}
            </button>
          )}
          <div className="ml-auto flex gap-3">
            <button
              type="button"
              onClick={onClose}
              disabled={busy}
              className="h-11 px-4 rounded-lg bg-white/5 border border-white/10 text-white/80 hover:bg-white/10"
            >
              {t('vault.cancel')}
            </button>
            <button
              type="button"
              onClick={handleSave}
              disabled={busy}
              className="h-11 px-5 rounded-lg bg-cyan-500/30 border border-cyan-400/40 text-white hover:bg-cyan-500/45 disabled:opacity-50"
            >
              {busy ? t('vault.saving') : isCreate ? t('vault.create') : t('vault.save')}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}


// ─── Main panel ─────────────────────────────────────────────────────────────


export function VaultPanel() {
  const { t } = useTranslation();
  const [cards, setCards] = useState<VaultCard[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [kindFilter, setKindFilter] = useState<VaultCardKind | 'all'>('all');
  const [includeDeleted, setIncludeDeleted] = useState(false);

  const [editing, setEditing] = useState<{ card: VaultCard | null; kind: VaultCardKind } | null>(
    null,
  );
  const [createPickerOpen, setCreatePickerOpen] = useState(false);

  async function reload() {
    setLoading(true);
    setError(null);
    try {
      const res = await vaultApi.list({ includeDeleted });
      setCards(res.cards);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [includeDeleted]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return cards.filter((c) => {
      if (kindFilter !== 'all' && c.kind !== kindFilter) return false;
      if (q) {
        const hay = [
          c.label,
          c.kind,
          ...c.tags,
          ...Object.entries(c.fields)
            .filter(([, v]) => v !== '***')
            .map(([k, v]) => `${k}:${v}`),
        ]
          .join(' ')
          .toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });
  }, [cards, search, kindFilter]);

  return (
    <div className="vault-panel h-full overflow-y-auto p-6">
      <div className="flex items-start justify-between mb-4 gap-4">
        <div>
          <div className="text-xl font-semibold text-white/95">{t('vault.title')}</div>
          <div className="text-xs text-white/50 mt-1">
            {t('vault.intro')}
          </div>
        </div>
        <button
          type="button"
          onClick={() => setCreatePickerOpen(true)}
          className="h-11 px-5 shrink-0 rounded-lg bg-cyan-500/30 border border-cyan-400/40 text-white hover:bg-cyan-500/45"
        >
          {t('vault.newCardBtn')}
        </button>
      </div>

      <div className="flex gap-3 mb-4 flex-wrap">
        <input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder={t('vault.searchPh')}
          className="flex-1 min-w-[240px] h-11 px-3 rounded-lg bg-white/5 border border-white/10 text-white/95 outline-none focus:border-cyan-400/60"
        />
        <select
          value={kindFilter}
          onChange={(e) => setKindFilter(e.target.value as VaultCardKind | 'all')}
          className="h-11 px-3 rounded-lg bg-white/5 border border-white/10 text-white/95 outline-none"
        >
          <option value="all">{t('vault.allKinds')}</option>
          {kindConfigs().map((c) => (
            <option key={c.id} value={c.id}>
              {c.icon} {c.label}
            </option>
          ))}
        </select>
        <button
          type="button"
          onClick={() => setIncludeDeleted((v) => !v)}
          className={`h-11 px-4 rounded-lg border text-sm ${
            includeDeleted
              ? 'bg-rose-500/15 border-rose-400/30 text-rose-200'
              : 'bg-white/5 border-white/10 text-white/60'
          }`}
        >
          {includeDeleted ? t('vault.deletedShown') : t('vault.showDeleted')}
        </button>
      </div>

      {loading && <div className="text-white/60 text-sm">{t('vault.loading')}</div>}
      {error && (
        <div className="p-3 rounded bg-rose-500/15 border border-rose-400/30 text-rose-200 text-sm mb-4">
          {error}
        </div>
      )}

      {!loading && filtered.length === 0 && (
        <div className="text-center py-16 text-white/40">
          <div className="text-5xl mb-3">🔐</div>
          <div className="text-base">{t('vault.empty')}</div>
          <div className="text-xs mt-1">
            {t('vault.emptyHint')}
          </div>
        </div>
      )}

      <div className="flex flex-wrap gap-4">
        {filtered.map((card) => (
          <CardTile
            key={card.id}
            card={card}
            onOpen={() => {
              if (card.deleted_at) {
                if (confirm(t('vault.restoreConfirm', { label: card.label }))) {
                  void vaultApi.restore(card.id).then(reload);
                }
                return;
              }
              setEditing({ card, kind: card.kind });
            }}
          />
        ))}
      </div>

      {createPickerOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-6"
          onClick={() => setCreatePickerOpen(false)}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            className="w-full max-w-2xl rounded-3xl border border-white/15 bg-zinc-950/95 p-6"
          >
            <div className="text-lg font-semibold text-white/95 mb-4">
              {t('vault.pickKind')}
            </div>
            <div className="grid grid-cols-3 gap-3">
              {kindConfigs().map((cfg) => (
                <button
                  key={cfg.id}
                  type="button"
                  onClick={() => {
                    setCreatePickerOpen(false);
                    setEditing({ card: null, kind: cfg.id });
                  }}
                  className="rounded-xl border border-white/10 bg-white/5 hover:bg-white/10 p-4 text-left"
                >
                  <div className="text-3xl mb-1">{cfg.icon}</div>
                  <div className="text-sm text-white/90">{cfg.label}</div>
                  <div className="text-[10px] text-white/40 mt-1">
                    {cfg.fields.length} {tPlural(cfg.fields.length, 'plural.field')}
                  </div>
                </button>
              ))}
            </div>
          </div>
        </div>
      )}

      {editing && (
        <CardEditor
          card={editing.card}
          draftKind={editing.kind}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            void reload();
          }}
        />
      )}
    </div>
  );
}
