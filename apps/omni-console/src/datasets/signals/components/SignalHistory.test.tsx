import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { DatasetWidgetDefinition } from '../../../dashboard/types';
import { getSignalHistory } from '../api';
import type { SignalHistoryResponse } from '../types';
import { SignalHistory } from './SignalHistory';

vi.mock('../api', () => ({
  getSignalHistory: vi.fn(),
}));

const definition: DatasetWidgetDefinition = {
  id: 'signals.history',
  dataset: 'signals',
  title: 'Signal history',
  requiredPartitions: [],
  supportedFilters: [],
  defaultSize: 'large',
  component: SignalHistory,
};

const response: SignalHistoryResponse = {
  effectiveDataDate: '2026-09-06',
  generatedAt: '2026-09-06T12:00:00Z',
  dataVersions: { signals: `sha256:${'a'.repeat(64)}` },
  truncated: false,
  exchange: 'HOSE',
  strategy: 'CONFIRMED_TREND_EQUALS',
  availableExchanges: ['HOSE'],
  symbol: null,
  limit: 10,
  history: [
    {
      code: 'HPG',
      signalDate: '2026-09-06',
      signal: 'BULLISH',
      price: 28000,
      score: 0.5,
      reasonCodes: ['EQUAL_VOTE_SCORE_0.5'],
      modelVersion: 'CONFIRMED_TREND_EQUALS_V1',
      components: [
        {
          strategy: 'TREND_MOMENTUM_V1',
          signal: 'BULLISH',
          mappedValue: 1,
          score: 5,
          signalDate: '2026-09-06',
          reasonCodes: ['PRICE_ABOVE_MA50'],
        },
        {
          strategy: 'ICHIMOKU_V1',
          signal: 'NEUTRAL',
          mappedValue: 0,
          score: 1,
          signalDate: '2026-09-06',
          reasonCodes: ['PRICE_INSIDE_CLOUD'],
        },
      ],
      actualReturnT5: null,
      actualReturnT10: null,
      actualReturnT15: null,
      actualReturnT20: null,
    },
  ],
};

const getSignalHistoryMock = vi.mocked(getSignalHistory);

describe('SignalHistory', () => {
  beforeEach(() => {
    getSignalHistoryMock.mockReset();
    getSignalHistoryMock.mockResolvedValue(response);
  });

  it('defaults to confirmed trend and exposes component evidence', async () => {
    render(<SignalHistory definition={definition} />);

    await screen.findByText('HPG');
    expect(getSignalHistoryMock).toHaveBeenCalledWith(
      expect.any(AbortSignal),
      null,
      '',
      'CONFIRMED_TREND_EQUALS',
      10
    );

    fireEvent.click(screen.getByText('Components'));
    expect(screen.getByText('TREND_MOMENTUM_V1: BULLISH')).toBeInTheDocument();
    expect(screen.getByText('ICHIMOKU_V1: NEUTRAL')).toBeInTheDocument();
  });

  it('requests the selected strategy and exact submitted symbol', async () => {
    render(<SignalHistory definition={definition} />);
    await screen.findByText('HPG');

    fireEvent.change(screen.getByLabelText('Signal history strategy'), {
      target: { value: 'ICHIMOKU_V1' },
    });
    fireEvent.change(await screen.findByLabelText('Signal history symbol'), {
      target: { value: 'hpg' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }));

    await waitFor(() =>
      expect(getSignalHistoryMock).toHaveBeenLastCalledWith(
        expect.any(AbortSignal),
        null,
        'HPG',
        'ICHIMOKU_V1',
        10
      )
    );
  });
});
