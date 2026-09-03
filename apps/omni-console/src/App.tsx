import { useCallback, useEffect, useState } from 'react';

import { listDatasets, type DatasetManifest } from './api';
import { DatasetExplorer } from './components/DatasetExplorer';
import { JobsPanel } from './components/JobsPanel';
import { SqlConsole } from './components/SqlConsole';
import { DashboardPage } from './dashboard/DashboardPage';

type View = 'explorer' | 'sql' | 'jobs' | 'dashboard';

const navigation: { id: View; label: string }[] = [
  { id: 'dashboard', label: 'Market Dashboard' },
  { id: 'jobs', label: 'Jobs' },
  { id: 'explorer', label: 'Dataset Explorer' },
  { id: 'sql', label: 'SQL Console' },
];

export function App() {
  const [view, setView] = useState<View>('dashboard');
  const [manifest, setManifest] = useState<DatasetManifest | null>(null);
  const selectManifest = useCallback(
    (selected: DatasetManifest) => setManifest(selected),
    []
  );

  useEffect(() => {
    listDatasets()
      .then((datasets) => {
        if (datasets.length === 0) setView('explorer');
      })
      .catch(() => undefined);
  }, []);

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
        {navigation.map((item) => (
          <button
            key={item.id}
            className={view === item.id ? 'active' : ''}
            aria-current={view === item.id ? 'page' : undefined}
            onClick={() => setView(item.id)}
          >
            {item.label}
          </button>
        ))}
      </nav>
      <div className="content">
        {view === 'explorer' && <DatasetExplorer onSelect={selectManifest} />}
        {view === 'sql' && <SqlConsole manifest={manifest} />}
        {view === 'jobs' && <JobsPanel />}
        {view === 'dashboard' && <DashboardPage />}
      </div>
    </div>
  );
}
