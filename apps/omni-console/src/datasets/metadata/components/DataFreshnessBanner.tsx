import { useCallback } from 'react';

import { WidgetStateView } from '../../../dashboard/shared/WidgetStateView';
import type { DatasetWidgetProps, WidgetState } from '../../../dashboard/types';
import { useWidgetRequest } from '../../../dashboard/useWidgetRequest';
import { getDashboardFreshness } from '../api';
import type { FreshnessResponse } from '../types';

const STALE_AFTER_HOURS = 48;

export function DataFreshnessBanner({ definition }: DatasetWidgetProps) {
  const request = useCallback(
    (signal: AbortSignal) => getDashboardFreshness(signal),
    []
  );
  const toState = useCallback(
    (data: FreshnessResponse): WidgetState<FreshnessResponse> => {
      if (data.datasets.length === 0) {
        return { status: 'empty', message: 'No datasets are cataloged.' };
      }
      const latestDate = data.datasets
        .map((item) => item.effectiveDataDate)
        .filter((value): value is string => Boolean(value))
        .sort()
        .at(-1);
      const latestGeneratedAt = data.datasets
        .map((item) => item.generatedAt)
        .filter((value): value is string => Boolean(value))
        .sort()
        .at(-1);
      const now = Date.now();
      const stale = data.datasets.some((item) => {
        const generated = Date.parse(item.generatedAt ?? '');
        return (
          item.status !== 'READY' ||
          !Number.isFinite(generated) ||
          generated > now ||
          now - generated > STALE_AFTER_HOURS * 60 * 60 * 1000
        );
      });
      return {
        status: stale ? 'stale' : 'ready',
        data,
        stale,
        provenance: {
          effectiveDataDate: latestDate ?? null,
          generatedAt: latestGeneratedAt ?? data.generatedAt,
          dataVersions: Object.fromEntries(
            data.datasets
              .filter((item) => item.dataVersion)
              .map((item) => [item.dataset, item.dataVersion as string])
          ),
        },
      } as WidgetState<FreshnessResponse>;
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
          <p className="muted">
            Latest catalog partitions only; this does not establish exact-date
            work coverage or I10 READY.
          </p>
          <ul className="freshness-list">
            {data.datasets.slice(0, 6).map((item) => (
              <li key={item.dataset}>
                <strong>{item.dataset}</strong>
                <span>
                  {item.effectiveDataDate ?? 'Unknown date'} · Catalog:{' '}
                  {item.status}
                </span>
              </li>
            ))}
          </ul>
          {data.datasets.length > 6 && (
            <p className="muted">
              Showing 6 of {data.datasets.length} catalog datasets. Freshness
              checks include every returned dataset.
            </p>
          )}
        </>
      )}
    />
  );
}
