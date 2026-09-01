import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import type { DatasetWidgetDefinition } from '../types';
import { WidgetStateView } from './WidgetStateView';

const definition: DatasetWidgetDefinition = {
  id: 'test.widget',
  dataset: 'test',
  title: 'Test widget',
  requiredPartitions: [],
  supportedFilters: [],
  defaultSize: 'small',
  component: () => null,
};

const provenance = {
  effectiveDataDate: '2026-08-28',
  generatedAt: '2026-08-28T12:00:00Z',
  dataVersions: { test: 'sha256:abc' },
};

describe('WidgetStateView', () => {
  it.each([
    ['loading', { status: 'loading' }],
    ['empty', { status: 'empty', message: 'No rows', provenance }],
    ['unavailable', { status: 'unavailable', message: 'Missing source' }],
    ['error', { status: 'error', message: 'Request failed' }],
  ] as const)('renders the %s state', (status, state) => {
    render(
      <WidgetStateView
        definition={definition}
        state={state}
        renderReady={() => <span>Ready payload</span>}
      />
    );

    expect(screen.getByText(status)).toBeInTheDocument();
  });

  it.each(['ready', 'stale'] as const)(
    'renders data and provenance for %s state',
    (status) => {
      const state =
        status === 'stale'
          ? ({ status, data: { value: 42 }, provenance, stale: true } as const)
          : ({
              status,
              data: { value: 42 },
              provenance,
              stale: false,
            } as const);
      render(
        <WidgetStateView
          definition={definition}
          state={state}
          renderReady={(data) => <span>Value {data.value}</span>}
        />
      );

      expect(screen.getByText('Value 42')).toBeInTheDocument();
      expect(screen.getByText('2026-08-28')).toBeInTheDocument();
      expect(screen.getByText('1')).toBeInTheDocument();
    }
  );

  it('invokes the shared refresh action', () => {
    const refresh = vi.fn();
    render(
      <WidgetStateView
        definition={definition}
        state={{ status: 'unavailable', message: 'Missing source' }}
        onRefresh={refresh}
        renderReady={() => null}
      />
    );

    fireEvent.click(
      screen.getByRole('button', { name: 'Refresh Test widget' })
    );
    expect(refresh).toHaveBeenCalledOnce();
  });

  it('disables refresh while loading', () => {
    render(
      <WidgetStateView
        definition={definition}
        state={{ status: 'loading' }}
        onRefresh={vi.fn()}
        renderReady={() => null}
      />
    );

    expect(
      screen.getByRole('button', { name: 'Refresh Test widget' })
    ).toBeDisabled();
  });

  it('invokes the bounded retry action for errors', () => {
    const retry = vi.fn();
    render(
      <WidgetStateView
        definition={definition}
        state={{ status: 'error', message: 'Request failed', retry }}
        renderReady={() => null}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(retry).toHaveBeenCalledOnce();
  });
});
