import { useCallback, useState } from 'react';

import { WidgetStateView } from '../../../dashboard/shared/WidgetStateView';
import type { DatasetWidgetProps, WidgetState } from '../../../dashboard/types';
import { useWidgetRequest } from '../../../dashboard/useWidgetRequest';
import { getIchimokuSignals } from '../api';
import type { IchimokuSignalsResponse } from '../types';

type Exchange = 'HOSE' | 'HNX' | 'UPCOM';
type SignalLimit = 5 | 10 | 20;

const exchanges: Exchange[] = ['HOSE', 'HNX', 'UPCOM'];
const limits: SignalLimit[] = [5, 10, 20];

export function LatestSignalFeed({ definition }: DatasetWidgetProps) {
  const [exchange, setExchange] = useState<Exchange>('HOSE');
  const [limit, setLimit] = useState<SignalLimit>(10);
  const request = useCallback(
    (signal: AbortSignal) => getIchimokuSignals(signal, exchange, limit),
    [exchange, limit]
  );
  const toState = useCallback(
    (data: IchimokuSignalsResponse): WidgetState<IchimokuSignalsResponse> =>
      data.signals.length === 0
        ? {
            status: 'empty',
            message: 'No precomputed Ichimoku signals are available.',
          }
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
          <div className="mover-selectors signal-selectors">
            <label className="mover-selector">
              <span>Exchange</span>
              <select
                aria-label="Ichimoku exchange"
                value={exchange}
                onChange={(event) =>
                  setExchange(event.target.value as Exchange)
                }
              >
                {exchanges.map((value) => (
                  <option key={value}>{value}</option>
                ))}
              </select>
            </label>
            <label className="mover-selector">
              <span>Show</span>
              <select
                aria-label="Number of Ichimoku signals"
                value={limit}
                onChange={(event) =>
                  setLimit(Number(event.target.value) as SignalLimit)
                }
              >
                {limits.map((value) => (
                  <option key={value}>{value}</option>
                ))}
              </select>
            </label>
          </div>
          <ol className="signal-list">
            {data.signals.map((item) => (
              <li key={item.code}>
                <div>
                  <strong>{item.code}</strong>
                  <span className={`signal-badge ${item.signal.toLowerCase()}`}>
                    {item.signal}
                  </span>
                </div>
                <div className="signal-score">
                  Score {item.score > 0 ? '+' : ''}
                  {item.score}
                </div>
                <small>{item.reasonCodes.map(formatReason).join(' · ')}</small>
              </li>
            ))}
          </ol>
        </>
      )}
    />
  );
}

function formatReason(reason: string): string {
  return reason.toLowerCase().replaceAll('_', ' ');
}
