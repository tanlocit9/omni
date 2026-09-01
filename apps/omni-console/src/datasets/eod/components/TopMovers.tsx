import { useCallback, useState } from 'react';

import { WidgetStateView } from '../../../dashboard/shared/WidgetStateView';
import type { DatasetWidgetProps, WidgetState } from '../../../dashboard/types';
import { useWidgetRequest } from '../../../dashboard/useWidgetRequest';
import { getTopMovers } from '../api';
import type { TopMover, TopMoversResponse } from '../types';

type MoverTab = 'gainers' | 'losers';
type MoverLimit = 5 | 10 | 20;
type Exchange = 'HOSE' | 'HNX' | 'UPCOM';

const limits: MoverLimit[] = [5, 10, 20];
const exchanges: Exchange[] = ['HOSE', 'HNX', 'UPCOM'];

export function TopMovers({ definition }: DatasetWidgetProps) {
  const [activeTab, setActiveTab] = useState<MoverTab>('gainers');
  const [limit, setLimit] = useState<MoverLimit>(5);
  const [exchange, setExchange] = useState<Exchange>('HOSE');
  const request = useCallback(
    (signal: AbortSignal) => getTopMovers(signal, exchange, limit),
    [exchange, limit]
  );
  const toState = useCallback(
    (data: TopMoversResponse): WidgetState<TopMoversResponse> =>
      data.gainers.length === 0 && data.losers.length === 0
        ? { status: 'empty', message: 'No comparable EOD rows are available.' }
        : {
            status: 'ready',
            data,
            stale: false,
            provenance: {
              effectiveDataDate: data.effectiveDataDate,
              generatedAt: data.generatedAt,
              dataVersions: data.dataVersions,
            },
          },
    []
  );
  const { state, refresh } = useWidgetRequest(request, toState);

  return (
    <WidgetStateView
      definition={definition}
      state={state}
      onRefresh={refresh}
      renderReady={(data) => (
        <>
          <div className="mover-controls">
            <div
              className="mover-tabs"
              role="tablist"
              aria-label="Mover direction"
            >
              {(['gainers', 'losers'] as const).map((tab) => (
                <button
                  key={tab}
                  id={`${definition.id}-${tab}-tab`}
                  role="tab"
                  aria-selected={activeTab === tab}
                  aria-controls={`${definition.id}-${tab}-panel`}
                  className={activeTab === tab ? 'active' : ''}
                  onClick={() => setActiveTab(tab)}
                >
                  {tab === 'gainers' ? 'Gainers' : 'Losers'}
                </button>
              ))}
            </div>
            <div className="mover-selectors">
              <label className="mover-selector">
                <span>Exchange</span>
                <select
                  value={exchange}
                  aria-label="Exchange"
                  onChange={(event) =>
                    setExchange(event.target.value as Exchange)
                  }
                >
                  {exchanges.map((value) => (
                    <option value={value} key={value}>
                      {value}
                    </option>
                  ))}
                </select>
              </label>
              <label className="mover-selector">
                <span>Top</span>
                <select
                  value={limit}
                  aria-label="Number of movers"
                  onChange={(event) =>
                    setLimit(Number(event.target.value) as MoverLimit)
                  }
                >
                  {limits.map((value) => (
                    <option value={value} key={value}>
                      {value}
                    </option>
                  ))}
                </select>
              </label>
            </div>
          </div>
          <MoverList
            definitionId={definition.id}
            tab={activeTab}
            rows={data[activeTab]}
          />
        </>
      )}
    />
  );
}

function MoverList({
  definitionId,
  tab,
  rows,
}: {
  definitionId: string;
  tab: MoverTab;
  rows: TopMover[];
}) {
  return (
    <div
      id={`${definitionId}-${tab}-panel`}
      role="tabpanel"
      aria-labelledby={`${definitionId}-${tab}-tab`}
    >
      {rows.length === 0 ? (
        <p className="muted">No {tab} for the effective date.</p>
      ) : (
        <ol className="mover-list">
          {rows.map((row) => (
            <li key={row.code}>
              <strong>{row.code.toUpperCase()}</strong>
              <span className={tab === 'gainers' ? 'positive' : 'negative'}>
                {row.changePercent >= 0 ? '+' : ''}
                {row.changePercent.toFixed(2)}%
              </span>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
