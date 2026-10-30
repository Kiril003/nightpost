import { LayoutPanelTop } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';

interface Props {
  values: Record<string, unknown>;
  onChange: (key: string, value: unknown) => void;
}

type LayoutMode = 'conversation' | 'telemetry';

export function AgentLayoutGroup({ values, onChange }: Props) {
  const { t } = useTranslation();
  const current = (values['ui_agent_layout'] as LayoutMode | undefined) ?? 'conversation';

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
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
        <LayoutPanelTop size={14} strokeWidth={1.75} style={{ color: 'var(--ph-lit-amber-deep)' }} />
        <span
          style={{
            fontSize: 12,
            fontWeight: 700,
            letterSpacing: '0.06em',
            textTransform: 'uppercase',
            color: 'var(--ink-primary)',
          }}
        >
          {t('settings.agent.layout.title')}
        </span>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        {(['conversation', 'telemetry'] as LayoutMode[]).map((mode) => {
          const active = current === mode;
          const label = t(mode === 'conversation' ? 'settings.agent.layout.conversation' : 'settings.agent.layout.telemetry');
          return (
            <button
              key={mode}
              type="button"
              aria-pressed={active}
              data-testid={`agent-layout-${mode}`}
              onClick={() => onChange('ui_agent_layout', mode)}
              style={{
                minHeight: 44,
                display: 'flex',
                alignItems: 'center',
                gap: 10,
                padding: '6px 12px',
                borderRadius: 10,
                background: active ? 'var(--ph-lit-amber-a18)' : 'var(--ph-lit-white-a55)',
                border: active ? '1px solid var(--ph-lit-amber-a45)' : '1px solid var(--ph-lit-black-a7)',
                color: active ? 'var(--ph-lit-amber-ink)' : 'var(--ink-primary)',
                cursor: 'pointer',
                fontSize: 12,
                fontWeight: active ? 700 : 500,
                textAlign: 'left',
                transition: 'all 150ms ease',
              }}
            >
              <span
                style={{
                  width: 10,
                  height: 10,
                  borderRadius: 999,
                  background: active ? 'var(--ph-lit-amber)' : 'var(--ph-lit-black-a15)',
                  flexShrink: 0,
                  transition: 'background 150ms ease',
                }}
              />
              {label}
            </button>
          );
        })}
      </div>
    </div>
  );
}
