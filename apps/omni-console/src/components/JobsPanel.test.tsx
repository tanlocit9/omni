import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ApiError,
  getJobDefinition,
  listJobDefinitions,
  type JobDefinitionDetail,
} from '../api';
import { JobsPanel } from './JobsPanel';

vi.mock('../api', async (loadOriginal) => ({
  ...(await loadOriginal<typeof import('../api')>()),
  getJobDefinition: vi.fn(),
  listJobDefinitions: vi.fn(),
}));

const detail: JobDefinitionDetail = {
  id: '11111111-1111-1111-1111-111111111111',
  title: 'Indicator sync',
  source: 'ANALYZER',
  jobType: 'SYNC_INDICATORS',
  workType: 'SYNC_INDICATORS',
  workKey: 'SYNC_INDICATORS:ANALYZER',
  active: true,
  cronExpression: '0 0 * * * *',
  nextRun: '2026-08-25T03:00:00Z',
  triggerable: false,
  triggerBlockReason: 'Manual job triggering is deferred',
  lastExecution: null,
  dependencies: {
    jobs: ['SYNC_STOCK_PRICE'],
    datasets: ['eod'],
    produces: ['indicators'],
  },
  acceptedTriggerParameters: [],
  recentExecutions: [],
};

describe('read-only JobsPanel', () => {
  afterEach(() => cleanup());
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(listJobDefinitions).mockResolvedValue({
      items: [detail],
      page: 0,
      size: 100,
      total: 1,
    });
    vi.mocked(getJobDefinition).mockResolvedValue(detail);
  });

  it('retains catalog/dependencies/history without manual forms or trigger filters', async () => {
    render(<JobsPanel />);
    await screen.findByRole('heading', { name: 'Indicator sync' });
    expect(screen.getByText(/SYNC_STOCK_PRICE/)).toBeInTheDocument();
    expect(screen.getByText('No execution history.')).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: /trigger|submitting/i })
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('textbox', { name: /reason/i })
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('checkbox', { name: /allow-list/i })
    ).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Search jobs'), {
      target: { value: 'vci' },
    });
    await waitFor(() =>
      expect(listJobDefinitions).toHaveBeenLastCalledWith({
        q: 'vci',
        active: true,
      })
    );
  });

  it.each([
    [401, 'Your private operator session is missing or expired.'],
    [403, 'This operator is not allowed to perform that action.'],
    [404, 'The job definition or execution no longer exists.'],
    [500, 'server unavailable'],
  ])('retains actionable read errors for HTTP %s', async (status, expected) => {
    vi.mocked(listJobDefinitions).mockRejectedValue(
      new ApiError(expected, status)
    );
    render(<JobsPanel />);
    expect(await screen.findByText(expected)).toBeInTheDocument();
  });

  it('retains an empty catalog state', async () => {
    vi.mocked(listJobDefinitions).mockResolvedValue({
      items: [],
      page: 0,
      size: 100,
      total: 0,
    });
    render(<JobsPanel />);
    expect(
      await screen.findByText('No matching definitions.')
    ).toBeInTheDocument();
  });
});
