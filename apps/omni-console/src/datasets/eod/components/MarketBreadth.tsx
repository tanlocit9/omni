import { useCallback } from 'react';

import { WidgetStateView } from '../../../dashboard/shared/WidgetStateView';
import type { DatasetWidgetProps, WidgetState } from '../../../dashboard/types';
import { useWidgetRequest } from '../../../dashboard/useWidgetRequest';
import { getMarketBreadth } from '../api';
import type { MarketBreadthResponse } from '../types';

export function MarketBreadth({ definition }: DatasetWidgetProps) {
  const request = useCallback(
    (signal: AbortSignal) => getMarketBreadth(signal),
    []
  );
  const toState = useCallback(
    (data: MarketBreadthResponse): WidgetState<MarketBreadthResponse> => {
      if (data.metrics.total === 0) {
        return {
          status: 'empty',
          message: 'No EOD rows exist for the effective date.',
        };
      }
      return {
        status: 'ready',
        data,
        stale: false,
        provenance: {
          effectiveDataDate: data.effectiveDataDate,
          generatedAt: data.generatedAt,
          dataVersions: data.dataVersions,
        },
      };
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
        <dl className="breadth-metrics">
          <div className="positive">
            <dt>Advancing</dt>
            <dd>{data.metrics.advancing}</dd>
          </div>
          <div className="negative">
            <dt>Declining</dt>
            <dd>{data.metrics.declining}</dd>
          </div>
          <div>
            <dt>Unchanged</dt>
            <dd>{data.metrics.unchanged}</dd>
          </div>
        </dl>
      )}
    />
  );
}
