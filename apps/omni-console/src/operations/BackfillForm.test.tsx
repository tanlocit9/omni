import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import { createBackfill, getBackfillGoals, getBackfillSectors } from './api';
import { BackfillForm } from './BackfillForm';
import type { BackfillAccepted, BackfillGoal } from './types';

vi.mock('./api', () => ({
  getBackfillGoals: vi.fn(),
  getBackfillSectors: vi.fn(),
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
  dataPolicy: 'PREFER_EXISTING',
  membershipPolicy: 'CURRENT_ACTIVE_AT_ACCEPTANCE',
};

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(getBackfillGoals).mockResolvedValue(goals);
  vi.mocked(getBackfillSectors).mockResolvedValue([
    { code: 'BANK', name: 'Banks' },
    { code: 'STEEL', name: 'Steel' },
  ]);
  vi.mocked(createBackfill).mockResolvedValue(accepted);
});
afterEach(() => vi.useRealTimers());

async function openForm(onAccepted = vi.fn()) {
  render(<BackfillForm onAccepted={onAccepted} />);
  await screen.findByText('BANK - Banks');
  return onAccepted;
}

function selectSectors(codes: string[]) {
  const summary = screen.getByLabelText('Sector codes');
  if (!summary.closest('details')?.open) fireEvent.click(summary);
  for (const input of screen.getAllByRole('checkbox', {
    hidden: true,
  }) as HTMLInputElement[]) {
    if (input.checked !== codes.includes(input.value)) fireEvent.click(input);
  }
}

function fillScope() {
  selectSectors(['BANK', 'STEEL']);
  fireEvent.change(screen.getByLabelText('From date'), {
    target: { value: '01/09/2026' },
  });
  fireEvent.change(screen.getByLabelText('To date'), {
    target: { value: '02/09/2026' },
  });
}

function submit() {
  fireEvent.click(screen.getByRole('button', { name: 'Create backfill' }));
}

describe('New backfill', () => {
  it('groups day/month/year fields in one date range and preserves ISO API dates', async () => {
    await openForm();
    fillScope();
    const range = screen.getByRole('group', {
      name: 'Date range · dd/mm/yyyy',
    });
    expect(range).toContainElement(screen.getByLabelText('From date'));
    expect(range).toContainElement(screen.getByLabelText('To date'));
    expect(screen.getByLabelText('From date')).toHaveValue('01/09/2026');
    expect(screen.getByLabelText('To date')).toHaveValue('02/09/2026');
    submit();
    await waitFor(() =>
      expect(createBackfill).toHaveBeenCalledWith(
        expect.objectContaining({
          dateFrom: '2026-09-01',
          dateTo: '2026-09-02',
        }),
        expect.any(String),
        expect.any(AbortSignal)
      )
    );
  });

  it('formats calendar selections and numeric entry as day/month/year', async () => {
    await openForm();
    fillScope();
    fireEvent.change(screen.getByLabelText('Choose from date'), {
      target: { value: '2026-08-30' },
    });
    fireEvent.change(screen.getByLabelText('To date'), {
      target: { value: '02092026' },
    });
    expect(screen.getByLabelText('From date')).toHaveValue('30/08/2026');
    expect(screen.getByLabelText('To date')).toHaveValue('02/09/2026');
    submit();
    await waitFor(() =>
      expect(createBackfill).toHaveBeenCalledWith(
        expect.objectContaining({
          dateFrom: '2026-08-30',
          dateTo: '2026-09-02',
        }),
        expect.any(String),
        expect.any(AbortSignal)
      )
    );
  });

  it.each(['31/02/2026', '29/02/2025', '13/13/2026', '1/9/2026'])(
    'rejects invalid calendar date %s without dispatching',
    async (value) => {
      await openForm();
      fillScope();
      fireEvent.change(screen.getByLabelText('From date'), {
        target: { value },
      });
      submit();
      expect(await screen.findByRole('alert')).toHaveTextContent('dd/mm/yyyy');
      expect(createBackfill).not.toHaveBeenCalled();
    }
  );

  it('accepts a valid leap day without swapping the day and month', async () => {
    await openForm();
    fillScope();
    fireEvent.change(screen.getByLabelText('From date'), {
      target: { value: '29/02/2024' },
    });
    fireEvent.change(screen.getByLabelText('To date'), {
      target: { value: '01/03/2024' },
    });
    submit();
    await waitFor(() =>
      expect(createBackfill).toHaveBeenCalledWith(
        expect.objectContaining({
          dateFrom: '2024-02-29',
          dateTo: '2024-03-01',
        }),
        expect.any(String),
        expect.any(AbortSignal)
      )
    );
  });

  it('defaults to recommended level 2 and displays database sector names and codes', async () => {
    await openForm();
    expect(screen.getByLabelText('Sector level')).toHaveValue('2');
    expect(
      screen.getByRole('option', { name: '2 (Recommended)' })
    ).toBeInTheDocument();
    expect(getBackfillSectors).toHaveBeenCalledWith(2, expect.any(AbortSignal));
    fireEvent.click(screen.getByLabelText('Sector codes'));
    expect(
      screen.getByRole('checkbox', { name: 'BANK - Banks' })
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('textbox', { name: 'Sector codes' })
    ).not.toBeInTheDocument();
  });

  it('clears selection on a level change and ignores a stale catalog response', async () => {
    let finishOld!: (items: Array<{ code: string; name: string }>) => void;
    await openForm();
    fillScope();
    vi.mocked(getBackfillSectors)
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishOld = resolve;
          })
      )
      .mockResolvedValueOnce([{ code: 'GROUP', name: 'Group' }]);
    fireEvent.change(screen.getByLabelText('Sector level'), {
      target: { value: '1' },
    });
    expect(
      screen.getByRole('button', { name: 'Create backfill' })
    ).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Sector level'), {
      target: { value: '3' },
    });
    await screen.findByText('GROUP - Group');
    await act(async () => finishOld([{ code: 'STALE', name: 'Stale' }]));
    expect(screen.queryByText('STALE - Stale')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Sector codes')).toHaveTextContent(
      'Select sector codes'
    );
    selectSectors(['GROUP']);
    submit();
    await waitFor(() => expect(createBackfill).toHaveBeenCalled());
    expect(vi.mocked(createBackfill).mock.calls[0][0]).toMatchObject({
      sectorLevel: 3,
      sectorCodes: ['GROUP'],
    });
  });

  it('blocks submission on unavailable sectors and supports an explicit retry', async () => {
    vi.mocked(getBackfillSectors).mockRejectedValueOnce(
      new ApiError('offline', 503)
    );
    render(<BackfillForm onAccepted={vi.fn()} />);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Sector catalog is unavailable'
    );
    expect(
      screen.getByRole('button', { name: 'Create backfill' })
    ).toBeDisabled();
    fireEvent.submit(
      screen.getByRole('button', { name: 'Create backfill' }).closest('form')!
    );
    expect(createBackfill).not.toHaveBeenCalled();
    fireEvent.click(
      screen.getByRole('button', { name: 'Retry sector discovery' })
    );
    await screen.findByText('BANK - Banks');
    fillScope();
    submit();
    await waitFor(() => expect(createBackfill).toHaveBeenCalledTimes(1));
  });

  it('shows an empty catalog without inventing options', async () => {
    vi.mocked(getBackfillSectors).mockResolvedValue([]);
    render(<BackfillForm onAccepted={vi.fn()} />);
    await screen.findByText(
      'No sectors with active symbols are available at this level.'
    );
    expect(screen.getByLabelText('Sector codes')).toHaveTextContent(
      'No sectors available at this level'
    );
    expect(
      screen.getByRole('button', { name: 'Create backfill' })
    ).toBeDisabled();
    expect(
      screen.queryByRole('checkbox', { hidden: true })
    ).not.toBeInTheDocument();
  });

  it('limits selection to 100 available sectors', async () => {
    vi.mocked(getBackfillSectors).mockResolvedValue(
      Array.from({ length: 101 }, (_, i) => ({
        code: `S${i}`,
        name: `Sector ${i}`,
      }))
    );
    render(<BackfillForm onAccepted={vi.fn()} />);
    await screen.findByText('S0 - Sector 0');
    fireEvent.click(screen.getByLabelText('Sector codes'));
    const inputs = screen.getAllByRole('checkbox');
    for (const input of inputs.slice(0, 100)) fireEvent.click(input);
    expect(inputs[100]).toBeDisabled();
    fireEvent.click(inputs[0]);
    expect(inputs[100]).not.toBeDisabled();
  });

  it('submits Symbols as a current exchange snapshot without sector or date selectors', async () => {
    vi.mocked(getBackfillGoals).mockResolvedValue([
      ...goals,
      {
        goal: 'SYNC_SYMBOLS',
        targets: [{ targetDefinitionId: secondTarget, title: 'Symbols' }],
      },
    ]);
    await openForm();
    fillScope();
    fireEvent.change(screen.getByLabelText('Goal'), {
      target: { value: 'SYNC_SYMBOLS' },
    });
    expect(screen.queryByLabelText('Sector codes')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('From date')).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Exchange'), {
      target: { value: 'HNX' },
    });
    fireEvent.change(screen.getByLabelText('Data policy'), {
      target: { value: 'PREFER_EXISTING' },
    });
    submit();
    await waitFor(() => expect(createBackfill).toHaveBeenCalledTimes(1));
    expect(vi.mocked(createBackfill).mock.calls[0][0]).toEqual({
      goal: 'SYNC_SYMBOLS',
      targetDefinitionId: secondTarget,
      exchange: 'HNX',
      dataPolicy: 'PREFER_EXISTING',
    });
  });

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

  it.each([['PREFER_EXISTING'], ['REFRESH']] as const)(
    'submits the %s policy and normalized explicit scope',
    async (policy) => {
      const onAccepted = await openForm();
      fillScope();
      fireEvent.change(screen.getByLabelText('Sector level'), {
        target: { value: '2' },
      });
      fireEvent.change(screen.getByLabelText('Data policy'), {
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
          dataPolicy: policy,
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
      expect(screen.getByLabelText('Sector codes')).toHaveAttribute(
        'aria-disabled',
        'true'
      );
    }
  );

  it.each(['empty-code', 'reversed', 'too-long', 'future', 'missing-date'])(
    'rejects %s scope before creating work',
    async (scenario) => {
      await openForm();
      fillScope();
      if (scenario === 'empty-code') selectSectors([]);
      if (scenario === 'reversed')
        fireEvent.change(screen.getByLabelText('To date'), {
          target: { value: '31/08/2026' },
        });
      if (scenario === 'too-long')
        fireEvent.change(screen.getByLabelText('From date'), {
          target: { value: '01/01/2025' },
        });
      if (scenario === 'future')
        fireEvent.change(screen.getByLabelText('To date'), {
          target: { value: '01/01/9999' },
        });
      if (scenario === 'missing-date')
        fireEvent.change(screen.getByLabelText('From date'), {
          target: { value: '' },
        });
      submit();
      expect(await screen.findByRole('alert')).toBeInTheDocument();
      expect(createBackfill).not.toHaveBeenCalled();
    }
  );

  it('uses the Vietnam calendar date for the future-date boundary', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-09-01T18:00:00Z'));
    await act(async () => {
      render(<BackfillForm onAccepted={vi.fn()} />);
    });
    expect(screen.getByLabelText('Choose to date')).toHaveAttribute(
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
    selectSectors(['BANK']);
    submit();
    await waitFor(() => expect(createBackfill).toHaveBeenCalledTimes(2));
    expect(vi.mocked(createBackfill).mock.calls[1][1]).not.toBe(previous);
  });

  it('retains the key when the same sectors are reselected in a different order after uncertain delivery', async () => {
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
    selectSectors([]);
    fireEvent.click(screen.getByLabelText('STEEL - Steel'));
    fireEvent.click(screen.getByLabelText('BANK - Banks'));
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
