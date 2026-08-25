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
  getTriggerStatus,
  listJobDefinitions,
  triggerJob,
  type JobDefinitionDetail,
  type ManualTriggerResponse,
} from '../api';
import { JobsPanel } from './JobsPanel';

vi.mock('../api', async (loadOriginal) => {
  const original = await loadOriginal<typeof import('../api')>();
  return {
    ...original,
    getJobDefinition: vi.fn(),
    getTriggerStatus: vi.fn(),
    listJobDefinitions: vi.fn(),
    triggerJob: vi.fn(),
  };
});

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
  triggerable: true,
  triggerBlockReason: null,
  lastExecution: null,
  dependencies: {
    jobs: ['SYNC_STOCK_PRICE'],
    datasets: ['eod'],
    produces: ['indicators'],
  },
  acceptedTriggerParameters: [],
  recentExecutions: [],
};

const accepted: ManualTriggerResponse = {
  requestId: '22222222-2222-2222-2222-222222222222',
  definitionId: detail.id,
  executionId: '33333333-3333-3333-3333-333333333333',
  state: 'ACCEPTED',
  duplicate: false,
  blockReason: null,
  error: null,
  requestedAt: '2026-08-25T01:00:00Z',
  resolvedAt: '2026-08-25T01:00:01Z',
};

describe('JobsPanel', () => {
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
    vi.stubGlobal(
      'confirm',
      vi.fn(() => true)
    );
    vi.stubGlobal('crypto', { randomUUID: () => 'request-key-1' });
  });

  it('renders accessible catalog, detail, dependencies, loading and reason validation', async () => {
    render(<JobsPanel />);

    expect(screen.getByLabelText('Job operations')).toBeInTheDocument();
    expect(screen.getByText('Loading definitions…')).toBeInTheDocument();
    await screen.findByRole('heading', { name: 'Indicator sync' });
    expect(screen.getByText(/SYNC_STOCK_PRICE/)).toBeInTheDocument();
    expect(screen.getByText(/eod/)).toBeInTheDocument();
    expect(screen.getByText('No execution history.')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Review & trigger' })
    ).toBeDisabled();
    expect(
      screen.getByRole('textbox', { name: 'Operational reason (required)' })
    ).toBeEnabled();
  });

  it('requires confirmation and prevents duplicate clicks while submission is pending', async () => {
    let resolveTrigger!: (value: ManualTriggerResponse) => void;
    vi.mocked(triggerJob).mockReturnValue(
      new Promise((resolve) => {
        resolveTrigger = resolve;
      })
    );
    render(<JobsPanel />);
    await screen.findByRole('heading', { name: 'Indicator sync' });
    fireEvent.change(
      screen.getByRole('textbox', { name: 'Operational reason (required)' }),
      { target: { value: 'Recover delayed indicators' } }
    );
    const button = screen.getByRole('button', { name: 'Review & trigger' });

    fireEvent.click(button);
    fireEvent.click(button);

    expect(window.confirm).toHaveBeenCalledTimes(1);
    expect(triggerJob).toHaveBeenCalledTimes(1);
    expect(triggerJob).toHaveBeenCalledWith(
      detail.id,
      'Recover delayed indicators',
      'request-key-1'
    );
    expect(screen.getByRole('button', { name: 'Submitting…' })).toBeDisabled();
    resolveTrigger({
      ...accepted,
      state: 'BLOCKED',
      executionId: null,
      blockReason: 'EOD is not READY',
    });
    expect(await screen.findByText('EOD is not READY')).toBeInTheDocument();
  });

  it('does not submit when the operator cancels confirmation', async () => {
    vi.mocked(window.confirm).mockReturnValue(false);
    render(<JobsPanel />);
    await screen.findByRole('heading', { name: 'Indicator sync' });
    fireEvent.change(
      screen.getByRole('textbox', { name: 'Operational reason (required)' }),
      { target: { value: 'Check only' } }
    );

    fireEvent.click(screen.getByRole('button', { name: 'Review & trigger' }));

    expect(triggerJob).not.toHaveBeenCalled();
  });

  it('polls an accepted request to a terminal execution state', async () => {
    vi.mocked(triggerJob).mockResolvedValue(accepted);
    vi.mocked(getTriggerStatus).mockResolvedValue({
      trigger: accepted,
      execution: {
        id: accepted.executionId!,
        status: 'SUCCESS',
        triggeredAt: accepted.requestedAt,
        startedAt: accepted.requestedAt,
        finishedAt: accepted.resolvedAt,
        recordsSynced: 42,
        recordsSkipped: 0,
        error: null,
      },
    });
    render(<JobsPanel />);
    await screen.findByRole('heading', { name: 'Indicator sync' });
    fireEvent.change(
      screen.getByRole('textbox', { name: 'Operational reason (required)' }),
      { target: { value: 'Run now' } }
    );

    fireEvent.click(screen.getByRole('button', { name: 'Review & trigger' }));

    expect(await screen.findByText(accepted.executionId!)).toBeInTheDocument();
    await waitFor(
      () => expect(getTriggerStatus).toHaveBeenCalledWith(accepted.requestId),
      {
        timeout: 2000,
      }
    );
    expect(await screen.findByText('SUCCESS')).toBeInTheDocument();
  });

  it.each([
    [401, 'Your private operator session is missing or expired.'],
    [403, 'This operator is not allowed to perform that action.'],
    [404, 'The job definition or execution no longer exists.'],
    [409, 'already running'],
    [422, 'invalid request'],
    [500, 'server unavailable'],
  ])('shows an actionable error for HTTP %s', async (status, expected) => {
    vi.mocked(listJobDefinitions).mockRejectedValue(
      new ApiError(expected, status)
    );

    render(<JobsPanel />);

    expect(await screen.findByText(expected)).toBeInTheDocument();
  });

  it('shows empty and server-unavailable catalog states', async () => {
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
    expect(screen.getByText('Select a job definition.')).toBeInTheDocument();
  });
});
