import { Component, type ErrorInfo, type ReactNode } from 'react';

interface State {
  err: Error | null;
}

export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { err: null };

  static getDerivedStateFromError(err: Error): State {
    return { err };
  }

  componentDidCatch(err: Error, info: ErrorInfo): void {
    console.error('[ErrorBoundary]', err, info);
  }

  render() {
    if (this.state.err) {
      return (
        <div
          style={{
            minHeight: '100vh',
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            padding: 24,
            gap: 16,
            background:
              'radial-gradient(900px 600px at 18% 10%, #fff5e2 0%, transparent 60%), radial-gradient(1100px 700px at 82% 90%, #ffe9d0 0%, transparent 60%), #f6f1e8',
            color: '#2a1f10',
            fontFamily: 'var(--display)',
            textAlign: 'center',
          }}
        >
          <div style={{ fontFamily: 'var(--mono)', fontSize: 11, letterSpacing: '0.22em', color: '#9a8a6f', fontWeight: 700 }}>
            PHANTOM · COMPANION
          </div>
          <div style={{ fontSize: 22, fontWeight: 700 }}>Something cracked.</div>
          <div style={{ fontFamily: 'var(--mono)', fontSize: 12, color: '#6a5a44', maxWidth: 320 }}>
            {this.state.err.message}
          </div>
          <button
            onClick={() => {
              this.setState({ err: null });
              location.reload();
            }}
            style={{
              padding: '10px 18px',
              borderRadius: 999,
              background: '#F4AF25',
              color: '#1a1208',
              fontFamily: 'var(--mono)',
              fontWeight: 700,
              letterSpacing: '0.18em',
              fontSize: 11,
              textTransform: 'uppercase',
            }}
          >
            Reload
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}
