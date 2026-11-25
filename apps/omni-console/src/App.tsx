import { useCallback, useEffect, useRef, useState } from 'react';

import { listDatasets, type DatasetManifest } from './api';
import { DatasetExplorer } from './components/DatasetExplorer';
import { JobsPanel } from './components/JobsPanel';
import { SqlConsole } from './components/SqlConsole';
import { DashboardPage } from './dashboard/DashboardPage';
import { WorkOperations } from './operations/WorkOperations';

type View = 'explorer' | 'sql' | 'jobs' | 'dashboard' | 'work';

const navigation: { id: View; label: string }[] = [
  { id: 'dashboard', label: 'Market Dashboard' },
  { id: 'jobs', label: 'Jobs' },
  { id: 'work', label: 'Work Operations' },
  { id: 'explorer', label: 'Dataset Explorer' },
  { id: 'sql', label: 'SQL Console' },
];

function readRoute(): { view: View; requestId: string | null } {
  const params = new URLSearchParams(window.location.search);
  const requestId = params.get('requestId')?.trim() || null;
  const view =
    navigation.find((item) => item.id === params.get('view'))?.id ??
    (requestId ? 'work' : 'dashboard');
  return { view, requestId };
}

export function App() {
  const [route, setRoute] = useState(readRoute);
  const { view, requestId } = route;
  const explicitNavigation = useRef(
    new URLSearchParams(window.location.search).has('view') ||
      new URLSearchParams(window.location.search).has('requestId')
  );
  const navigate = (nextView: View, nextRequestId: string | null = null) => {
    explicitNavigation.current = true;
    const url = new URL(window.location.href);
    url.searchParams.set('view', nextView);
    if (nextRequestId) url.searchParams.set('requestId', nextRequestId);
    else url.searchParams.delete('requestId');
    window.history.pushState(null, '', url);
    setRoute({ view: nextView, requestId: nextRequestId });
  };
  const [manifest, setManifest] = useState<DatasetManifest | null>(null);
  const selectManifest = useCallback(
    (selected: DatasetManifest | null) => setManifest(selected),
    []
  );

  useEffect(() => {
    const onPopState = () => {
      explicitNavigation.current = true;
      setRoute(readRoute());
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);

  useEffect(() => {
    let current = true;
    listDatasets()
      .then((datasets) => {
        if (current && datasets.length === 0 && !explicitNavigation.current) {
          setRoute({ view: 'explorer', requestId: null });
        }
      })
      .catch(() => undefined);
    return () => {
      current = false;
    };
  }, []);

  return (
    <div className="app-shell min-h-screen bg-console-canvas">
      <header className="flex min-h-18 items-center gap-3.5 border-0 border-b border-solid border-console-line bg-console-panel px-(--page-gutter) py-3">
        <div className="brand-mark">O</div>
        <div>
          <h1>Omni Console</h1>
          <p>Operations · Metadata · Query</p>
        </div>
        <div className="environment">
          <span />
          PRIVATE
        </div>
      </header>
      <nav
        aria-label="Console sections"
        className="flex flex-wrap gap-1 border-0 border-b border-solid border-console-line bg-console-panel px-(--page-gutter) py-2"
      >
        {navigation.map((item) => (
          <button
            key={item.id}
            className={view === item.id ? 'active' : ''}
            aria-current={view === item.id ? 'page' : undefined}
            onClick={() => navigate(item.id)}
          >
            {item.label}
          </button>
        ))}
      </nav>
      <div className="mx-auto max-w-[1720px] px-(--page-gutter) pt-6 pb-10 [@media(max-width:760px)]:pt-4 [@media(max-width:760px)]:pb-7.5">
        {view === 'explorer' && (
          <DatasetExplorer
            onSelect={selectManifest}
            onOpenWork={() => navigate('work')}
          />
        )}
        {view === 'sql' && <SqlConsole manifest={manifest} />}
        {view === 'jobs' && <JobsPanel onOpenWork={() => navigate('work')} />}
        {view === 'work' && (
          <WorkOperations
            requestId={requestId}
            onRequestChange={(id) => navigate('work', id)}
          />
        )}
        {view === 'dashboard' && <DashboardPage />}
      </div>
    </div>
  );
}
