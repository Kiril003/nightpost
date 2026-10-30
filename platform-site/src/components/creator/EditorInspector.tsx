import { Pencil, X } from 'lucide-react';
import { sizable, type Action, type EditPage } from '../../lib/creator/editor';
import { TONE_VAR, toned } from '../../lib/creator/inspector';
import type { Look } from '../../lib/creator/looks';
import type { EditorCopy } from '../../i18n/editor';
import { inspectorCopy } from '../../i18n/editorInspector';
import type { Lang } from '../../i18n/types';
import { KIND_ICON, LookPicker, WhoSees, blockTitle, kindLabel } from './EditorPanels';
import { AlsoShows, FieldForm, SizeSwatches, ToneSwatches } from './EditorFields';

export type InspectorTab = 'content' | 'look' | 'who';
type Tab = InspectorTab;

export function Inspector({ block, page, look, lang, t, tab, setTab, act, venue, add, error, touched, touch, onClose }: {
  block: EditPage['blocks'][number]; page: EditPage; look: Look; lang: Lang; t: EditorCopy; tab: Tab; setTab: (t: Tab) => void; act: (a: Action) => void; venue: string;
  add: (kind: string, stay?: boolean) => string | null; error?: { field: string; refused: string }; touched: Set<string>; touch: (k: string) => void; onClose: () => void;
}) {
  const Icon = KIND_ICON[block.kind] ?? Pencil;
  const it = inspectorCopy[lang];
  const tabs: [Tab, string][] = [['content', t.content], ['look', t.look], ['who', t.whoSees]];
  const tone = block.tone && TONE_VAR[block.tone];
  return (
    <div className="ed-insp">
      <header className="ed-insp-head">
        <span className="ed-kind" aria-hidden="true" style={tone ? { background: `var(${tone.bg})`, color: `var(${tone.ink})` } : undefined}><Icon size={18} /></span>
        <div className="ed-grow">
          <h2 className="ed-h">{kindLabel(block.kind, lang)}</h2>
          {blockTitle(block) && <p className="ed-hint ed-clip">{blockTitle(block)}</p>}
        </div>
        <button type="button" className="ed-btn icon" aria-label={t.close} onClick={onClose}><X size={18} aria-hidden="true" /></button>
      </header>
      <div className="ed-tabs" role="tablist">
        {tabs.map(([k, label]) => (
          <button key={k} type="button" role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{label}</button>
        ))}
      </div>
      <div className="ed-insp-body">
        {tab === 'content' && (
          <div className="ei-form">
            {sizable(block.kind, look) && (
              <section className="ei-sec">
                <span className="ed-label">{t.size}</span>
                <SizeSwatches block={block} look={look} lang={lang} t={t} act={act} />
              </section>
            )}
            <FieldForm ctx={{ page, block, lang, t, venue, act, add, error }} touched={touched} touch={touch} />
            {toned(block.kind) && (
              <section className="ei-sec">
                <span className="ed-label">{it.cardTone}</span>
                <ToneSwatches block={block} lang={lang} act={act} />
              </section>
            )}
            <AlsoShows block={block} page={page} lang={lang} act={act} />
          </div>
        )}
        {tab === 'look' && (
          <div className="ed-form">
            <div className="ed-field">
              <span className="ed-label">{t.pageLook}</span>
              <LookPicker look={look} lang={lang} t={t} onPick={(l) => act({ t: 'look', look: l })} />
            </div>
          </div>
        )}
        {tab === 'who' && <WhoSees block={block} lang={lang} t={t} act={act} />}
      </div>
    </div>
  );
}
