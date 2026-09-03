import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { App } from './App';

vi.stubGlobal(
  'fetch',
  vi.fn((input: RequestInfo | URL) => {
    const url = String(input);
    const payload = url.includes('/dashboard/freshness')
      ? {
          generatedAt: '2026-08-31T12:00:00Z',
          datasets: [
            {
              dataset: 'eod',
              status: 'READY',
              generatedAt: '2026-08-31T11:00:00Z',
              effectiveDataDate: '2026-08-29',
              dataVersion: `sha256:${'a'.repeat(64)}`,
              partitionCount: 2,
            },
          ],
        }
      : url.includes('/dashboard/market-breadth')
      ? {
          effectiveDataDate: '2026-08-29',
          generatedAt: '2026-08-31T11:00:00Z',
          dataVersions: { AAA: `sha256:${'a'.repeat(64)}` },
          truncated: false,
          metrics: { advancing: 1, declining: 0, unchanged: 0, total: 1 },
        }
      : url.includes('/dashboard/top-movers')
      ? {
          effectiveDataDate: '2026-08-29',
          generatedAt: '2026-08-31T11:00:00Z',
          dataVersions: { AAA: `sha256:${'a'.repeat(64)}` },
          truncated: false,
          limit: 5,
          gainers: [
            { code: 'AAA', close: 110, previousClose: 100, changePercent: 10 },
          ],
          losers: [
            { code: 'BBB', close: 90, previousClose: 100, changePercent: -10 },
          ],
        }
      : url.includes('/dashboard/ichimoku-signals')
      ? {
          effectiveDataDate: '2026-08-29',
          generatedAt: '2026-08-31T11:00:00Z',
          dataVersions: { signals: `sha256:${'a'.repeat(64)}` },
          truncated: false,
          exchange: 'HOSE',
          limit: 10,
          signals: [
            {
              code: 'AAA',
              signalDate: '2026-08-29',
              signal: 'BULLISH',
              price: 110,
              score: 4,
              reasonCodes: ['PRICE_ABOVE_CLOUD', 'SCORE_4'],
            },
          ],
        }
      : url.includes('/dashboard/signal-history')
      ? {
          effectiveDataDate: '2026-08-29',
          generatedAt: '2026-08-31T11:00:00Z',
          dataVersions: { signals: `sha256:${'b'.repeat(64)}` },
          truncated: false,
          exchange: 'HOSE',
          availableExchanges: ['HOSE'],
          symbol: null,
          limit: 10,
          history: [
            {
              code: 'AAA',
              signalDate: '2026-08-29',
              signal: 'BULLISH',
              price: 110,
              score: 4,
              reasonCodes: ['PRICE_ABOVE_MA50', 'SCORE_4'],
              actualReturnT5: 3.5,
              actualReturnT10: null,
              actualReturnT15: null,
              actualReturnT20: null,
            },
          ],
        }
      : [];
    return Promise.resolve({ ok: true, json: () => Promise.resolve(payload) });
  })
);

afterEach(cleanup);

describe('App', () => {
  it('opens on the fixed Market Dashboard', () => {
    render(<App />);

    expect(
      screen.getByRole('heading', { name: 'Omni Console' })
    ).toBeInTheDocument();
    expect(
      screen.getByRole('heading', { name: 'Market Dashboard' })
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Market Dashboard' })
    ).toHaveAttribute('aria-current', 'page');
    expect(screen.getByText('Market breadth')).toBeInTheDocument();
    expect(screen.getByText('Data freshness')).toBeInTheDocument();
    expect(screen.getAllByLabelText('Loading widget')).toHaveLength(5);
    expect(screen.getByText('Ichimoku signals')).toBeInTheDocument();
    expect(screen.getByText('Signal history')).toBeInTheDocument();
  });

  it.each([
    ['Dataset Explorer', 'Datasets'],
    ['SQL Console', 'SQL Console'],
    ['Jobs', 'Job catalog'],
  ])('keeps %s reachable', (buttonName, expectedHeading) => {
    render(<App />);

    fireEvent.click(screen.getByRole('button', { name: buttonName }));

    expect(screen.getByRole('button', { name: buttonName })).toHaveAttribute(
      'aria-current',
      'page'
    );
    expect(
      screen.getByRole('heading', { name: expectedHeading })
    ).toBeInTheDocument();
  });

  it('opens metadata recovery controls when no datasets exist', async () => {
    render(<App />);

    expect(
      await screen.findByRole('button', { name: 'Sync EOD metadata' })
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Sync indicator metadata' })
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Sync all metadata' })
    ).toBeInTheDocument();
  });

  it('returns to the Market Dashboard after using an operator tool', () => {
    render(<App />);

    fireEvent.click(screen.getByRole('button', { name: 'Dataset Explorer' }));
    fireEvent.click(screen.getByRole('button', { name: 'Market Dashboard' }));

    expect(
      screen.getByRole('heading', { name: 'Market Dashboard' })
    ).toBeInTheDocument();
  });
});
