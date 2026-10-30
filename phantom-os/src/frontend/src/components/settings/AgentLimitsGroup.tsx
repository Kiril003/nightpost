import { Infinity as InfinityIcon } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n/messages/uk';

const UNBOUND_KEYS = [
  'agent_max_actions_per_task',
  'agent_max_llm_calls_per_task',
  'agent_max_llm_calls_per_background_task',
] as const;

interface Props {
  values: Record<string, unknown>;
  onChange: (key: string, value: unknown) => void;
}

interface CapField {
  key: string;
  label: MessageKey;
  min: number;
  unboundable: boolean;
}

const FIELDS: CapField[] = [
  { key: 'agent_max_actions_per_task',            label: 'settings.agent.limits.maxActions',      min: 0, unboundable: true },
  { key: 'agent_max_elapsed_s_per_task',          label: 'settings.agent.limits.timeBudget', min: 0, unboundable: false },
  { key: 'agent_max_llm_calls_per_task',          label: 'settings.agent.limits.llmCalls',      min: 0, unboundable: true },
  { key: 'agent_max_llm_calls_per_background_task', label: 'settings.agent.limits.llmCallsBg',       min: 0, unboundable: true },
  { key: 'agent_bash_timeout_s',                  label: 'settings.agent.limits.bashTimeout',           min: 1, unboundable: false },
  { key: 'agent_bash_output_cap_bytes',           label: 'settings.agent.limits.bashCap',     min: 0, unboundable: false },
];

export function AgentLimitsGroup({ values, onChange }: Props) {
  const { t } = useTranslation();
  const unbound = !!values['agent_unbound_default'];

  function handleUnboundToggle() {
    const next = !unbound;
    if (next) {
      for (const key of UNBOUND_KEYS) onChange(key, 0);
    }
    onChange('agent_unbound_default', next);
  }

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 6,
        padding: '10px 12px',
        borderRadius: 12,
        background: 'var(--ph-lit-white-a45)',
        border: '1px solid var(--ph-lit-white-a55)',
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          marginBottom: 4,
        }}
      >
        <InfinityIcon size={14} strokeWidth={1.75} style={{ color: 'var(--ph-lit-amber-deep)' }} />
        <span
          style={{
            fontSize: 12,
            fontWeight: 700,
            letterSpacing: '0.06em',
            textTransform: 'uppercase',
            color: 'var(--ink-primary)',
          }}
        >
          {t('settings.agent.limits.title')}
        </span>
        <span style={{ flex: 1 }} />
        <label
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
            cursor: 'pointer',
          }}
        >
          <span
            style={{
              fontSize: 11,
              fontWeight: 600,
              color: unbound ? 'var(--ph-lit-amber-ink)' : 'var(--ink-secondary)',
            }}
          >
            {t('settings.agent.limits.unbound')}
          </span>
          <button
            type="button"
            aria-label={t('settings.agent.limits.unbound')}
            aria-pressed={unbound}
            onClick={handleUnboundToggle}
            style={{
              minHeight: 44,
              minWidth: 44,
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              background: 'transparent',
              border: 'none',
              cursor: 'pointer',
              padding: 0,
            }}
          >
            <span
              aria-hidden
              style={{
                position: 'relative',
                display: 'inline-block',
                width: 40,
                height: 22,
                borderRadius: 999,
                background: unbound
                  ? 'linear-gradient(135deg,var(--ph-lit-amber),var(--ph-lit-orange))'
                  : 'var(--ph-lit-black-a12)',
                transition: 'background 200ms ease',
              }}
            >
              <span
                style={{
                  position: 'absolute',
                  top: 3,
                  left: unbound ? 21 : 3,
                  width: 16,
                  height: 16,
                  borderRadius: 999,
                  background: 'white',
                  boxShadow: '0 1px 4px var(--ph-lit-black-a20)',
                  transition: 'left 200ms ease',
                }}
              />
            </span>
          </button>
        </label>
      </div>

      {unbound && (
        <div
          style={{
            fontSize: 11,
            color: 'var(--ph-lit-amber-ink)',
            background: 'var(--ph-lit-amber-a10)',
            border: '1px dashed var(--ph-lit-amber-a35)',
            borderRadius: 8,
            padding: '5px 10px',
          }}
        >
          {t('settings.agent.limits.unboundNote')}
        </div>
      )}

      {FIELDS.map((f) => {
        const disabled = unbound && f.unboundable;
        const raw = values[f.key];
        const num = Number.isFinite(raw as number) ? (raw as number) : 0;
        return (
          <div
            key={f.key}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 10,
              minHeight: 36,
              padding: '3px 0',
              opacity: disabled ? 0.45 : 1,
              transition: 'opacity 200ms ease',
            }}
          >
            <label
              htmlFor={`agent-limit-${f.key}`}
              style={{
                flex: 1,
                fontSize: 12,
                fontWeight: 500,
                color: 'var(--ink-primary)',
                cursor: disabled ? 'default' : 'pointer',
              }}
            >
              {t(f.label)}
            </label>
            <input
              id={`agent-limit-${f.key}`}
              aria-label={t(f.label)}
              type="number"
              min={f.min}
              disabled={disabled}
              value={num}
              onChange={(e) => {
                const n = e.target.value === '' ? 0 : Number(e.target.value);
                onChange(f.key, Number.isFinite(n) ? n : 0);
              }}
              className="tabular"
              style={{
                minHeight: 36,
                width: 110,
                padding: '0 10px',
                borderRadius: 8,
                color: 'var(--ink-primary)',
                background: disabled ? 'var(--ph-lit-black-a4)' : 'var(--ph-lit-white-a70)',
                border: '1px solid var(--ph-lit-black-a8)',
                fontFamily: 'var(--font-mono)',
                fontSize: 12,
                outline: 'none',
              }}
            />
          </div>
        );
      })}
    </div>
  );
}
