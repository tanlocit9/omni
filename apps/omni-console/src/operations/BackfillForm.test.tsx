import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import { createBackfill, getBackfillGoals } from './api';
import { BackfillForm } from './BackfillForm';
import type { BackfillAccepted, BackfillGoal } from './types';

vi.mock('./api', () => ({
  getBackfillGoals: vi.fn(),
  createBackfill: vi.fn(),
}));

const firstTarget = '11111111-1111-1111-1111-111111111111';
const secondTarget = '22222222-2222-2222-2222-222222222222';
const goals: BackfillGoal[] = [
  {
    goal: 'SYNC_SIGNALS',
    targets: [{ targetDefinitionId: firstTarget, title: 'Signals strategy' }],
  },
];
const accepted: BackfillAccepted = {
  requestId: 'request-1',
  executionId: 'execution-1',
  status: 'ACCEPTED',
  goal: 'SYNC_SIGNALS',
  rerun: false,
  force: false,
  membershipPolicy: 'CURRENT_ACTIVE_AT_ACCEPTANCE',
};

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(getBackfillGoals).mockResolvedValue(goals);
  vi.mocked(createBackfill).mockResolvedValue(accepted);
});
afterEach(() => vi.useRealTimers());

async function openForm(onAccepted = vi.fn()) {
  render(<BackfillForm onAccepted={onAccepted} />);
  await screen.findByLabelText('Sector codes');
  return onAccepted;
}

function fillScope() {
  fireEvent.change(screen.getByLabelText('Sector codes'), {
    target: { value: 'bank, BANK\nsteel' },
  });
  fireEvent.change(screen.getByLabelText('From date'), {
    target: { value: '2026-09-01' },
  });
  fireEvent.change(screen.getByLabelText('To date'), {
    target: { value: '2026-09-02' },
  });
}

function submit() {
  fireEvent.click(screen.getByRole('button', { name: 'Create backfill' }));
}

describe('New backfill', () => {
  it('loads goals from discovery and automatically selects a sole target', async () => {
    await openForm();
    expect(getBackfillGoals).toHaveBeenCalledWith(expect.any(AbortSignal));
    expect(screen.getByLabelText('Goal')).toHaveValue('SYNC_SIGNALS');
    expect(screen.getByLabelText('Target definition')).toHaveValue(firstTarget);
    expect(
      screen.queryByRole('option', { name: 'SYNC_INDICATORS' })
    ).not.toBeInTheDocument();
    expect(createBackfill).not.toHaveBeenCalled();
  });

  it('requires an explicit strategy when discovery returns several targets', async () => {
    vi.mocked(getBackfillGoals).mockResolvedValue([
      {
        goal: 'SYNC_SIGNALS',
        targets: [
          ...goals[0].targets,
          { targetDefinitionId: secondTarget, title: null },
        ],
      },
    ]);
    await openForm();
    fillScope();
    expect(screen.getByLabelText('Target definition')).toHaveValue('');
    submit();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Select an available goal and target definition.'
    );
    expect(createBackfill).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('Target definition'), {
      target: { value: secondTarget },
    });
    submit();
    await waitFor(() => expect(createBackfill).toHaveBeenCalled());
    expect(vi.mocked(createBackfill).mock.calls[0][0].targetDefinitionId).toBe(
      secondTarget
    );
  });

  it.each([
    ['default', false, false],
    ['recalculate', true, false],
    ['refresh', true, true],
  ] as const)(
    'submits the %s policy and normalized explicit scope',
    async (policy, rerun, force) => {
      const onAccepted = await openForm();
      fillScope();
      fireEvent.change(screen.getByLabelText('Sector level'), {
        target: { value: '2' },
      });
      fireEvent.change(screen.getByLabelText('Execution policy'), {
        target: { value: policy },
      });
      submit();
      await waitFor(() => expect(onAccepted).toHaveBeenCalledWith(accepted));
      expect(createBackfill).toHaveBeenCalledWith(
        {
          goal: 'SYNC_SIGNALS',
          targetDefinitionId: firstTarget,
          sectorLevel: 2,
          sectorCodes: ['BANK', 'STEEL'],
          dateFrom: '2026-09-01',
          dateTo: '2026-09-02',
          rerun,
          force,
        },
        expect.any(String),
        expect.any(AbortSignal)
      );
      expect(
        screen.getByText(/This is not execution completion or output READY/)
      ).toBeInTheDocument();
      expect(screen.getByText('request-1')).toBeInTheDocument();
      expect(
        screen.getByRole('button', { name: 'Create backfill' })
      ).toBeDisabled();
      expect(screen.getByLabelText('Sector codes')).toBeDisabled();
    }
  );

  it.each([
    'invalid-code',
    'empty-code',
    'too-many',
    'reversed',
    'too-long',
    'future',
    'missing-date',
  ])('rejects %s scope before creating work', async (scenario) => {
    await openForm();
    fillScope();
    if (scenario === 'invalid-code')
      fireEvent.change(screen.getByLabelText('Sector codes'), {
        target: { value: '../BANK' },
      });
    if (scenario === 'empty-code')
      fireEvent.change(screen.getByLabelText('Sector codes'), {
        target: { value: ',\n' },
      });
    if (scenario === 'too-many')
      fireEvent.change(screen.getByLabelText('Sector codes'), {
        target: {
          value: Array.from(
            { length: 101 },
            (_, index) => `SECTOR_${index}`
          ).join(','),
        },
      });
    if (scenario === 'reversed')
      fireEvent.change(screen.getByLabelText('To date'), {
        target: { value: '2026-08-31' },
      });
    if (scenario === 'too-long')
      fireEvent.change(screen.getByLabelText('From date'), {
        target: { value: '2025-01-01' },
      });
    if (scenario === 'future')
      fireEvent.change(screen.getByLabelText('To date'), {
        target: { value: '9999-01-01' },
      });
    if (scenario === 'missing-date')
      fireEvent.change(screen.getByLabelText('From date'), {
        target: { value: '' },
      });
    submit();
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(createBackfill).not.toHaveBeenCalled();
  });

  it('uses the Vietnam calendar date for the future-date boundary', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-09-01T18:00:00Z'));
    await act(async () => {
      render(<BackfillForm onAccepted={vi.fn()} />);
    });
    expect(screen.getByLabelText('To date')).toHaveAttribute(
      'max',
      '2026-09-02'
    );
    fillScope();
    await act(async () => submit());
    expect(createBackfill).toHaveBeenCalledTimes(1);
  });

  it('retains the key after uncertain failure, freezes an in-flight submit, and requires explicit new intent after acceptance', async () => {
    vi.mocked(createBackfill).mockRejectedValueOnce(
      new TypeError('network unavailable')
    );
    const onAccepted = await openForm();
    fillScope();
    submit();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Acceptance could not be confirmed'
    );
    expect(onAccepted).not.toHaveBeenCalled();
    const originalKey = vi.mocked(createBackfill).mock.calls[0][1];
    let complete!: (result: BackfillAccepted) => void;
    vi.mocked(createBackfill).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          complete = resolve;
        })
    );
    const form = screen
      .getByRole('button', { name: 'Create backfill' })
      .closest('form')!;
    fireEvent.submit(form);
    fireEvent.submit(form);
    expect(createBackfill).toHaveBeenCalledTimes(2);
    expect(vi.mocked(createBackfill).mock.calls[1][1]).toBe(originalKey);
    expect(screen.getByLabelText('Goal')).toBeDisabled();
    await act(async () => complete(accepted));
    expect(onAccepted).toHaveBeenCalledTimes(1);
    fireEvent.submit(form);
    expect(createBackfill).toHaveBeenCalledTimes(2);
    fireEvent.click(screen.getByRole('button', { name: 'New request' }));
    submit();
    await waitFor(() => expect(createBackfill).toHaveBeenCalledTimes(3));
    expect(vi.mocked(createBackfill).mock.calls[2][1]).not.toBe(originalKey);
  });

  it('uses a new key when an operator changes a failed request payload', async () => {
    vi.mocked(createBackfill).mockRejectedValue(
      new ApiError('Invalid sector membership', 400)
    );
    await openForm();
    fillScope();
    submit();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Invalid sector membership'
    );
    const previous = vi.mocked(createBackfill).mock.calls[0][1];
    fireEvent.change(screen.getByLabelText('Sector codes'), {
      target: { value: 'BANK' },
    });
    submit();
    await waitFor(() => expect(createBackfill).toHaveBeenCalledTimes(2));
    expect(vi.mocked(createBackfill).mock.calls[1][1]).not.toBe(previous);
  });

  it('retains the key when only sector whitespace, case or order changes after uncertain delivery', async () => {
    vi.mocked(createBackfill).mockRejectedValue(
      new TypeError('network unavailable')
    );
    await openForm();
    fillScope();
    submit();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Acceptance could not be confirmed'
    );
    const previous = vi.mocked(createBackfill).mock.calls[0][1];
    fireEvent.change(screen.getByLabelText('Sector codes'), {
      target: { value: ' STEEL, bank, steel ' },
    });
    submit();
    await waitFor(() => expect(createBackfill).toHaveBeenCalledTimes(2));
    expect(vi.mocked(createBackfill).mock.calls[1][1]).toBe(previous);
  });

  it.each([
    [401, 'Your private operator session is missing or expired.'],
    [403, 'This operator is not allowed to create backfills.'],
    [409, 'Backfill conflict'],
    [503, 'Acceptance could not be confirmed'],
  ])(
    'reports HTTP %s creation errors without accepted UI',
    async (status, expected) => {
      vi.mocked(createBackfill).mockRejectedValue(
        new ApiError('Backfill conflict', status)
      );
      const onAccepted = await openForm();
      fillScope();
      submit();
      expect(await screen.findByRole('alert')).toHaveTextContent(expected);
      expect(onAccepted).not.toHaveBeenCalled();
      expect(
        screen.queryByRole('button', { name: 'New request' })
      ).not.toBeInTheDocument();
    }
  );

  it('distinguishes goal loading, empty inventory and discovery failure with retry', async () => {
    vi.mocked(getBackfillGoals)
      .mockRejectedValueOnce(new ApiError('unavailable', 503))
      .mockResolvedValueOnce([]);
    render(<BackfillForm onAccepted={vi.fn()} />);
    expect(screen.getByRole('status')).toHaveTextContent(
      'Loading backfill goals'
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Backfill goals are unavailable'
    );
    expect(
      screen.queryByText('No eligible backfill goals are available.')
    ).not.toBeInTheDocument();
    fireEvent.click(
      screen.getByRole('button', { name: 'Retry goal discovery' })
    );
    expect(
      await screen.findByText('No eligible backfill goals are available.')
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Create backfill' })
    ).not.toBeInTheDocument();
  });
});
