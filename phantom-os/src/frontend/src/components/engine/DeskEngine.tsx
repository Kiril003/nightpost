import { useSearchParams } from 'react-router-dom';
import { DeskHq } from '../shell/DeskHq';
import { EngineFiles } from './files/EngineFiles';
import { tNow } from '../chat/tNow';

type Tab = 'hq' | 'files';
const TABS: readonly Tab[] = ['hq', 'files'];

export function DeskEngine() {
  const [params, setParams] = useSearchParams();
  const tab: Tab = params.get('tab') === 'files' ? 'files' : 'hq';
  return (
    <div className="h-full min-h-0 flex flex-col" data-testid="desk-engine">
      <div role="tablist" aria-label={tNow('deskShell.hq')} className="shrink-0 h-12 px-4 flex items-center gap-2">
        {TABS.map((k) => (
          <button
            key={k}
            type="button"
            role="tab"
            aria-selected={tab === k}
            onClick={() => setParams(k === 'hq' ? {} : { tab: k }, { replace: true })}
            className={`h-9 px-4 rounded-full text-f-label ${tab === k ? 'bg-f-raise text-f-ink' : 'text-f-ink-2 hover:bg-f-raise/60'}`}
          >
            {tNow(k === 'hq' ? 'engine.tab.hq' : 'engine.tab.files')}
          </button>
        ))}
      </div>
      <div role="tabpanel" className="flex-1 min-h-0 relative">
        {tab === 'hq' ? <DeskHq /> : <EngineFiles />}
      </div>
    </div>
  );
}
