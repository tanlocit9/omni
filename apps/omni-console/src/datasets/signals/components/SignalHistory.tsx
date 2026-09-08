import { useCallback, useState } from 'react';

import { WidgetStateView } from '../../../dashboard/shared/WidgetStateView';
import type { DatasetWidgetProps, WidgetState } from '../../../dashboard/types';
import { useWidgetRequest } from '../../../dashboard/useWidgetRequest';
import { getSignalHistory } from '../api';
import type {
  SignalHistoryResponse,
  SignalHistoryRow,
  SignalStrategy,
} from '../types';

type Exchange = 'HOSE' | 'HNX' | 'UPCOM';
type HistoryLimit = 5 | 10 | 20;

const limits: HistoryLimit[] = [5, 10, 20];
const strategies: SignalStrategy[] = [
  'CONFIRMED_TREND_EQUALS',
  'TREND_MOMENTUM_V1',
  'ICHIMOKU_V1',
];

export function SignalHistory({ definition }: DatasetWidgetProps) {
  const [exchange, setExchange] = useState<Exchange | null>(null);
  const [limit, setLimit] = useState<HistoryLimit>(10);
  const [symbolInput, setSymbolInput] = useState('');
  const [symbol, setSymbol] = useState('');
  const [strategy, setStrategy] = useState<SignalStrategy>(
    'CONFIRMED_TREND_EQUALS'
  );
  const request = useCallback(
    (signal: AbortSignal) =>
      getSignalHistory(signal, exchange, symbol, strategy, limit),
    [exchange, symbol, strategy, limit]
  );
  const toState = useCallback(
    (data: SignalHistoryResponse): WidgetState<SignalHistoryResponse> =>
      data.history.length === 0
        ? {
            status: 'empty',
            message: data.symbol
              ? `No ${data.strategy} history is available for ${data.symbol}.`
              : `No ${data.strategy} signal history is available.`,
            provenance: {
              effectiveDataDate: data.effectiveDataDate,
              generatedAt: data.generatedAt,
              dataVersions: data.dataVersions,
            },
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
          <form
            className="signal-history-controls"
            onSubmit={(event) => {
              event.preventDefault();
              setSymbol(symbolInput.trim().toUpperCase());
            }}
          >
            <label className="mover-selector">
              <span>Exchange</span>
              <select
                aria-label="Signal history exchange"
                value={exchange ?? data.exchange}
                onChange={(event) =>
                  setExchange(event.target.value as Exchange)
                }
              >
                {data.availableExchanges.map((value) => (
                  <option key={value}>{value}</option>
                ))}
              </select>
            </label>
            <label className="mover-selector">
              <span>Strategy</span>
              <select
                aria-label="Signal history strategy"
                value={strategy}
                onChange={(event) =>
                  setStrategy(event.target.value as SignalStrategy)
                }
              >
                {strategies.map((value) => (
                  <option key={value}>{value}</option>
                ))}
              </select>
            </label>
            <label className="mover-selector signal-symbol-filter">
              <span>Symbol</span>
              <input
                aria-label="Signal history symbol"
                value={symbolInput}
                pattern="[A-Za-z0-9]*"
                placeholder="All symbols"
                onChange={(event) =>
                  setSymbolInput(event.target.value.toUpperCase())
                }
              />
            </label>
            <button type="submit" className="secondary">
              Apply
            </button>
            <label className="mover-selector">
              <span>Rows</span>
              <select
                aria-label="Number of signal history rows"
                value={limit}
                onChange={(event) =>
                  setLimit(Number(event.target.value) as HistoryLimit)
                }
              >
                {limits.map((value) => (
                  <option key={value}>{value}</option>
                ))}
              </select>
            </label>
          </form>
          <div className="signal-history-table-wrap">
            <table className="signal-history-table">
              <thead>
                <tr>
                  <th>Symbol</th>
                  <th>Date</th>
                  <th>Signal</th>
                  <th>Score</th>
                  <th>Reasons</th>
                  <th>T+5</th>
                  <th>T+10</th>
                  <th>T+15</th>
                  <th>T+20</th>
                </tr>
              </thead>
              <tbody>
                {data.history.map((row, index) => (
                  <SignalHistoryTableRow
                    row={row}
                    key={`${row.code}-${row.signalDate}-${index}`}
                  />
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    />
  );
}

function SignalHistoryTableRow({ row }: { row: SignalHistoryRow }) {
  return (
    <tr>
      <td>
        <strong>{row.code}</strong>
      </td>
      <td>{row.signalDate}</td>
      <td>
        <span className={`signal-badge ${row.signal.toLowerCase()}`}>
          {row.signal}
        </span>
      </td>
      <td>
        {row.score > 0 ? '+' : ''}
        {row.score}
      </td>
      <td className="signal-reasons">
        {row.reasonCodes.map(formatReason).join(' · ')}
        {row.components && (
          <details>
            <summary>Components</summary>
            {row.components.map((component) => (
              <div key={component.strategy}>
                {component.strategy}: {component.signal}
              </div>
            ))}
          </details>
        )}
      </td>
      <OutcomeCell value={row.actualReturnT5} />
      <OutcomeCell value={row.actualReturnT10} />
      <OutcomeCell value={row.actualReturnT15} />
      <OutcomeCell value={row.actualReturnT20} />
    </tr>
  );
}

function OutcomeCell({ value }: { value: number | null }) {
  if (value === null) return <td className="muted">—</td>;
  return (
    <td className={value >= 0 ? 'positive' : 'negative'}>
      {value >= 0 ? '+' : ''}
      {value.toFixed(2)}%
    </td>
  );
}

function formatReason(reason: string): string {
  return reason.toLowerCase().replaceAll('_', ' ');
}
