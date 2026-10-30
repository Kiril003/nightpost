import { useNavigate } from 'react-router-dom';
import { PhoneShell } from '@/components/PhoneShell';
import { EmptyState } from '@/components/EmptyState';
import { useThemeContext } from '@/theme/ThemeProvider';

export function NotFoundScreen() {
  const { theme } = useThemeContext();
  const navigate = useNavigate();
  return (
    <PhoneShell theme={theme}>
      <div style={{ flex: 1, padding: 24, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <EmptyState
          theme={theme}
          icon="◌"
          title="Lost in the dark"
          hint="This route doesn't exist."
          cta={
            <button
              onClick={() => navigate('/pulse')}
              style={{
                padding: '10px 18px',
                borderRadius: 999,
                background: theme.primary,
                color: theme.isDark ? '#1a1208' : '#fff',
                fontFamily: 'var(--mono)',
                fontSize: 11,
                fontWeight: 700,
                letterSpacing: '0.18em',
                textTransform: 'uppercase',
              }}
            >
              Back to Pulse
            </button>
          }
        />
      </div>
    </PhoneShell>
  );
}
