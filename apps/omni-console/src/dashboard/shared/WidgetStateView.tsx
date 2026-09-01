import type { ReactNode } from 'react';

import type { DatasetWidgetDefinition, WidgetState } from '../types';
import { WidgetFrame } from './WidgetFrame';

type WidgetStateViewProps<T> = {
  definition: DatasetWidgetDefinition;
  state: WidgetState<T>;
  renderReady: (data: T) => ReactNode;
  onRefresh?: () => void;
};

export function WidgetStateView<T>({
  definition,
  state,
  renderReady,
  onRefresh,
}: WidgetStateViewProps<T>) {
  if (state.status === 'loading') {
    return (
      <WidgetFrame
        definition={definition}
        status="loading"
        onRefresh={onRefresh}
        refreshDisabled
      >
        <div className="widget-skeleton" aria-label="Loading widget" />
      </WidgetFrame>
    );
  }

  if (state.status === 'ready' || state.status === 'stale') {
    return (
      <WidgetFrame
        definition={definition}
        status={state.status}
        provenance={state.provenance}
        onRefresh={onRefresh}
      >
        {renderReady(state.data)}
      </WidgetFrame>
    );
  }

  return (
    <WidgetFrame
      definition={definition}
      status={state.status}
      description={state.message}
      provenance={state.status === 'empty' ? state.provenance : undefined}
      onRefresh={onRefresh}
    >
      {state.status === 'error' && state.retry && (
        <button className="secondary widget-retry" onClick={state.retry}>
          Retry
        </button>
      )}
    </WidgetFrame>
  );
}
