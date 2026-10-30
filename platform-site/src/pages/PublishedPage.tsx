import { useEffect, useState, type CSSProperties } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import '@fontsource-variable/source-serif-4/opsz.css';
import '@fontsource-variable/source-serif-4/opsz-italic.css';
import '@fontsource-variable/inter-tight';
import '@fontsource-variable/wix-madefor-display';
import CreatorPage from '../components/creator/CreatorPage';
import Layout from '../components/Layout';
import NotFound from './NotFound';
import { useLanguage } from '../i18n';
import { isLook, lookVars, type Look, type Theme } from '../lib/creator/looks';
import '../styles/creator.css';
import { creatorCopy } from '../lib/creator/format';
import { sampleByHandle } from '../lib/creator/samples';
import type { Live, Page } from '../lib/creator/arrange';
import { getPublishedPage } from '../lib/venuesApi';

type Loaded = { page: Page; live: Live; sample: boolean } | 'missing' | null;

export function useTheme(param: string | null): Theme {
  const query = typeof window !== 'undefined' && window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
  const [system, setSystem] = useState<Theme>(query?.matches ? 'night' : 'day');
  useEffect(() => {
    if (!query) return;
    const on = (e: MediaQueryListEvent) => setSystem(e.matches ? 'night' : 'day');
    query.addEventListener('change', on);
    return () => query.removeEventListener('change', on);
  }, [query]);
  return param === 'day' || param === 'night' ? param : system;
}

export default function PublishedPage() {
  const { handle = '' } = useParams();
  const [params] = useSearchParams();
  const { lang } = useLanguage();
  const theme = useTheme(params.get('theme'));
  const [loaded, setLoaded] = useState<Loaded>(() => {
    const s = sampleByHandle(handle);
    return s ? { page: s.page, live: s.live, sample: true } : null;
  });

  useEffect(() => {
    if (sampleByHandle(handle)) return;
    let alive = true;
    getPublishedPage(handle)
      .then((v) => {
        if (!alive) return;
        const page = v.page as Page | null;
        if (!page || page.v !== 2 || !v.published) return setLoaded('missing');
        setLoaded({ page, live: { network: 'devnet' }, sample: false });
      })
      .catch(() => alive && setLoaded('missing'));
    return () => {
      alive = false;
    };
  }, [handle]);

  const c = creatorCopy(lang);
  const loadedPage = loaded && loaded !== 'missing' ? loaded.page : null;
  useEffect(() => {
    const name = loadedPage?.blocks.find((b) => b.kind === 'id.name')?.data.name;
    if (name) document.title = name;
  }, [loadedPage]);

  const fromQuery = params.get('look');
  const look: Look = isLook(fromQuery) ? fromQuery : isLook(loadedPage?.look) ? (loadedPage!.look as Look) : 'shop';

  if (loaded === 'missing') {
    return (
      <Layout>
        <NotFound />
      </Layout>
    );
  }
  if (loaded === null) {
    return (
      <div className="cx" data-look={look} data-theme={theme} style={{ ...lookVars(look, theme), display: 'grid', placeItems: 'center' } as CSSProperties}>
        <div className="cx-wrap" aria-busy="true">
          <p className="cx-empty">{c.loading}</p>
        </div>
      </div>
    );
  }
  return <CreatorPage page={loaded.page} live={loaded.live} look={look} theme={theme} lang={lang} sample={loaded.sample} venueId={loaded.sample ? undefined : handle} />;
}
