import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { DatasetWidgetDefinition } from '../../../dashboard/types';
import { getDashboardFreshness } from '../api';
import type { FreshnessResponse } from '../types';
import { DataFreshnessBanner } from './DataFreshnessBanner';

// ---------------------------------------------------------------------------
// Module mock – api layer only; real widget stack (WidgetStateView, WidgetFrame,
// useWidgetRequest) is exercised to validate the full banner rendering path.
// ---------------------------------------------------------------------------
vi.mock('../api', () => ({
  getDashboardFreshness: vi.fn(),
}));

const getDashboardFreshnessMock = vi.mocked(getDashboardFreshness);

// ---------------------------------------------------------------------------
// Shared test fixture – typed DatasetWidgetDefinition for the banner widget.
// Constructed inline: the worker must not reach into the live registry.
// ---------------------------------------------------------------------------
const definition: DatasetWidgetDefinition = {
  id: 'metadata.freshness',
  dataset: 'catalog',
  title: 'Data Freshness',
  requiredPartitions: [],
  supportedFilters: [],
  defaultSize: 'medium',
  component: DataFreshnessBanner,
};

// ---------------------------------------------------------------------------
// Time helpers
//
// Use vi.spyOn(Date, 'now') to fix the wall-clock.  Fake timers are avoided
// because they conflict with findBy* async polling inside useWidgetRequest.
//
// ANCHOR_NOW  : fixed epoch ms used as the mocked "now" across all tests
// isoHoursAgo : returns an ISO string N hours before ANCHOR_NOW
// freshTs     : 1 h ago (within 48 h window, not stale on its own)
// staleTs     : 49 h ago (exceeds the 48 h threshold)
// futureTs    : 1 h after ANCHOR_NOW (clock-skew / bad-data guard)
// ---------------------------------------------------------------------------
const ANCHOR_NOW = new Date('2026-11-15T10:00:00.000Z').getTime();

function isoHoursAgo(hours: number): string {
  return new Date(ANCHOR_NOW - hours * 60 * 60 * 1000).toISOString();
}

const freshTs = isoHoursAgo(1); // 1 h ago  – within 48 h window
const staleTs = isoHoursAgo(49); // 49 h ago – exceeds 48 h threshold
const futureTs = new Date(ANCHOR_NOW + 60 * 60 * 1000).toISOString(); // future

// ---------------------------------------------------------------------------
// Minimal valid FreshnessResponse builders
// ---------------------------------------------------------------------------
function makeResponse(
  datasets: FreshnessResponse['datasets'],
  overrideGeneratedAt?: string
): FreshnessResponse {
  return {
    generatedAt: overrideGeneratedAt ?? freshTs,
    datasets,
  };
}

function readyItem(
  dataset: string,
  generatedAt: string,
  effectiveDataDate = '2026-11-14'
): FreshnessResponse['datasets'][number] {
  return {
    dataset,
    status: 'READY',
    generatedAt,
    effectiveDataDate,
    // dataVersion is not load-bearing for these tests but keeps the type valid
    dataVersion: 'sha256:' + dataset.slice(0, 64).padEnd(64, '0'),
  };
}

// ---------------------------------------------------------------------------
// Setup / teardown
// ---------------------------------------------------------------------------
let dateSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  dateSpy = vi.spyOn(Date, 'now').mockReturnValue(ANCHOR_NOW);
  getDashboardFreshnessMock.mockReset();
});

afterEach(() => {
  dateSpy.mockRestore();
});

// ===========================================================================
// Test suite
// ===========================================================================
describe('DataFreshnessBanner', () => {
  // -------------------------------------------------------------------------
  // Case 1 – All datasets READY and within 48 h → widget status: ready
  // -------------------------------------------------------------------------
  it('shows ready status when every catalog dataset is READY and within 48 h', async () => {
    getDashboardFreshnessMock.mockResolvedValue(
      makeResponse([
        readyItem('eod_prices', freshTs),
        readyItem('vn30_weights', freshTs),
      ])
    );

    render(<DataFreshnessBanner definition={definition} />);

    // WidgetFrame renders status as visible text: "ready"
    await screen.findByText('ready');

    // Both datasets listed in the per-row catalog output
    expect(screen.getByText('eod_prices')).toBeInTheDocument();
    expect(screen.getByText('vn30_weights')).toBeInTheDocument();

    // Scope note must be present for the I10 / exact-date disclaimer
    expect(
      screen.getByText(/exact-date work coverage or I10 READY/i)
    ).toBeInTheDocument();

    // WidgetFrame carries the class widget-status-ready on the status badge
    const statusEl = document.querySelector('.widget-status-ready');
    expect(statusEl).not.toBeNull();
  });

  // -------------------------------------------------------------------------
  // Case 2 – One fresh READY + one READY older than 48 h → stale
  //
  // Regression guard: the old banner used the *newest* generatedAt, hiding the
  // stale dataset. The corrected logic uses Array.some() so a single stale
  // dataset makes the whole widget stale.
  // -------------------------------------------------------------------------
  it('shows stale status when one dataset exceeds the 48 h threshold even if another is fresh', async () => {
    getDashboardFreshnessMock.mockResolvedValue(
      makeResponse([
        readyItem('eod_prices', freshTs), // 1 h ago – fresh
        readyItem('indicators', staleTs), // 49 h ago – stale
      ])
    );

    render(<DataFreshnessBanner definition={definition} />);

    await screen.findByText('stale');

    // Both rows must still render (slice(0,6) covers 2 datasets)
    expect(screen.getByText('eod_prices')).toBeInTheDocument();
    expect(screen.getByText('indicators')).toBeInTheDocument();

    const statusEl = document.querySelector('.widget-status-stale');
    expect(statusEl).not.toBeNull();
  });

  // -------------------------------------------------------------------------
  // Case 3 – One fresh READY + one UNAVAILABLE without timestamp → stale
  //
  // Missing generatedAt → Date.parse('') = NaN → !isFinite → stale.
  // The status !== 'READY' guard also fires first. Either path produces stale.
  // -------------------------------------------------------------------------
  it('shows stale status when a dataset is UNAVAILABLE and has no timestamp', async () => {
    getDashboardFreshnessMock.mockResolvedValue(
      makeResponse([
        readyItem('eod_prices', freshTs),
        {
          dataset: 'signals',
          status: 'UNAVAILABLE',
          // generatedAt intentionally absent
        },
      ])
    );

    render(<DataFreshnessBanner definition={definition} />);

    await screen.findByText('stale');

    // Per-row catalog status for the UNAVAILABLE entry must be visible
    expect(screen.getByText('signals')).toBeInTheDocument();
    expect(screen.getByText(/UNAVAILABLE/)).toBeInTheDocument();
  });

  // -------------------------------------------------------------------------
  // Case 4 – Invalid and future timestamps both resolve to stale (parameterized)
  //
  // - Invalid: Date.parse returns NaN → !isFinite → stale
  // - Future:  generated > now → stale (clock-skew / bad-data guard)
  // -------------------------------------------------------------------------
  it.each([
    ['invalid timestamp', 'not-a-date'],
    ['future timestamp', futureTs],
  ] as const)(
    'shows stale status for a READY dataset whose generatedAt is %s',
    async (_label, badTs) => {
      getDashboardFreshnessMock.mockResolvedValue(
        makeResponse([
          {
            dataset: 'eod_prices',
            status: 'READY',
            generatedAt: badTs,
            effectiveDataDate: '2026-11-14',
          },
        ])
      );

      render(<DataFreshnessBanner definition={definition} />);

      await screen.findByText('stale');
      expect(screen.getByText('eod_prices')).toBeInTheDocument();

      const statusEl = document.querySelector('.widget-status-stale');
      expect(statusEl).not.toBeNull();
    }
  );

  // -------------------------------------------------------------------------
  // Case 5 – More than 6 datasets: only first 6 rendered; disclosure shown
  //
  // The banner slices datasets to 6 for display but the stale check covers all
  // returned datasets. The "Showing 6 of N" disclosure must appear when
  // total > 6, and must state that freshness checks include every dataset.
  // -------------------------------------------------------------------------
  it('renders only the first 6 datasets and shows a disclosure when more exist', async () => {
    const datasets = Array.from({ length: 9 }, (_, i) =>
      readyItem('dataset_' + String(i + 1), freshTs)
    );

    getDashboardFreshnessMock.mockResolvedValue(makeResponse(datasets));

    render(<DataFreshnessBanner definition={definition} />);

    await screen.findByText('ready');

    // First six visible
    for (let i = 1; i <= 6; i++) {
      expect(screen.getByText('dataset_' + String(i))).toBeInTheDocument();
    }

    // 7th-9th must NOT be rendered
    expect(screen.queryByText('dataset_7')).toBeNull();
    expect(screen.queryByText('dataset_8')).toBeNull();
    expect(screen.queryByText('dataset_9')).toBeNull();

    // Disclosure paragraph: "Showing 6 of 9 catalog datasets"
    expect(screen.getByText(/Showing 6 of 9/i)).toBeInTheDocument();

    // Clarification that freshness covers every returned dataset, not just visible
    expect(
      screen.getByText(/Freshness checks include every returned dataset/i)
    ).toBeInTheDocument();
  });

  // -------------------------------------------------------------------------
  // Case 6 – Non-503 API failure → error state (not empty, not fresh/stale)
  //
  // useWidgetRequest maps non-503 rejections to { status: 'error' } and binds
  // a Retry callback. The banner must NOT silently show stale or empty content.
  // -------------------------------------------------------------------------
  it('shows error status on API failure and exposes a Retry action', async () => {
    getDashboardFreshnessMock.mockRejectedValue(
      new Error('Network request failed')
    );

    render(<DataFreshnessBanner definition={definition} />);

    await screen.findByText('error');

    // Error message must surface via WidgetFrame description
    expect(screen.getByText(/Network request failed/i)).toBeInTheDocument();

    // Must NOT show ready, stale, or empty status text
    expect(screen.queryByText('ready')).toBeNull();
    expect(screen.queryByText('stale')).toBeNull();

    // Retry button must be present (useWidgetRequest injects retry for non-503)
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();

    // Status class must be error on the WidgetFrame badge
    const statusEl = document.querySelector('.widget-status-error');
    expect(statusEl).not.toBeNull();
  });
});
