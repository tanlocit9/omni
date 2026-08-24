import { useCallback, useState } from 'react';

import type { DatasetManifest } from './api';
import { DatasetExplorer } from './components/DatasetExplorer';
import { JobsPanel } from './components/JobsPanel';
import { SqlConsole } from './components/SqlConsole';

type View = 'explorer' | 'sql' | 'jobs' | 'dashboard';

export function App() {
  const [view, setView] = useState<View>('explorer');
  const [manifest, setManifest] = useState<DatasetManifest | null>(null);
  const selectManifest = useCallback(
    (selected: DatasetManifest) => setManifest(selected),
    []
  );

  return (
    <div className="app-shell">
      <header>
        <div className="brand-mark">O</div>
        <div>
          <h1>Omni Console</h1>
          <p>Metadata · Query · Data health</p>
        </div>
        <div className="environment">
          <span />
          PRIVATE
        </div>
      </header>
      <nav aria-label="Console sections">
        <button
          className={view === 'jobs' ? 'active' : ''}
          onClick={() => setView('jobs')}
        >
          Jobs
        </button>
        <button
          className={view === 'explorer' ? 'active' : ''}
          onClick={() => setView('explorer')}
        >
          Dataset Explorer
        </button>
        <button
          className={view === 'sql' ? 'active' : ''}
          onClick={() => setView('sql')}
        >
          SQL Console
        </button>
        <button
          className={view === 'dashboard' ? 'active' : ''}
          onClick={() => setView('dashboard')}
        >
          Dashboard
        </button>
      </nav>
      <div className="content">
        {view === 'explorer' && <DatasetExplorer onSelect={selectManifest} />}
        {view === 'sql' && <SqlConsole manifest={manifest} />}
        {view === 'jobs' && <JobsPanel />}
        {view === 'dashboard' && (
          <section className="panel coming-soon">
            <p className="eyebrow">Next milestone</p>
            <h2>Saved Queries & Dashboard</h2>
            <p>
              The dashboard remains disabled until Saved Query ownership and
              persistence are implemented.
            </p>
          </section>
        )}
      </div>
    </div>
  );
}
