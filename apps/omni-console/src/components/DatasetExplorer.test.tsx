import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import {
  getDatasetPartitionPage,
  getJsonResult,
  listDatasets,
  submitQuery,
  waitForQuery,
  type DatasetManifest,
  type DatasetPartitionPage,
  type QueryStatus,
} from '../api';
import { DatasetExplorer } from './DatasetExplorer';

vi.mock('../api', async (loadOriginal) => ({
  ...(await loadOriginal<typeof import('../api')>()),
  listDatasets: vi.fn(),
  getDatasetPartitionPage: vi.fn(),
  submitQuery: vi.fn(),
  waitForQuery: vi.fn(),
  getJsonResult: vi.fn(),
}));

afterEach(() => cleanup());
beforeEach(() => vi.resetAllMocks());

function partition(symbol: string, dataset = 'eod'): DatasetManifest {
  return {
    dataset,
    partition: { symbol },
    status: 'READY',
    dataVersion: `version-${symbol}`,
    rowCount: 1,
    columnCount: 1,
    totalBytes: 100,
    columns: [{ name: 'close', type: 'DOUBLE', nullable: false }],
    generatedAt: '2026-11-15T00:00:00Z',
    minTimestamp: null,
    maxTimestamp: null,
    schemaVersion: 1,
    schemaHash: 'schema-one',
    objectCount: 1,
    inputs: [
      { dataset: 'symbols', partition: {}, dataVersion: 'symbols-frozen' },
    ],
    sourceExecutionId: 'execution-source',
  };
}

function catalog() {
  vi.mocked(listDatasets).mockResolvedValue([
    {
      name: 'eod',
      label: 'EOD',
      partitionCount: 3,
      partitionKeys: [
        {
          name: 'symbol',
          type: 'STRING',
          required: true,
          order: 0,
          queryable: true,
          label: 'Symbol',
        },
      ],
    },
    {
      name: 'indicators',
      label: 'Indicators',
      partitionCount: 1,
      partitionKeys: [],
    },
  ]);
}

function succeeded(queryId = 'query-one'): QueryStatus {
  return {
    queryId,
    state: 'SUCCEEDED',
    createdAt: '2026-11-15T00:00:00Z',
    startedAt: null,
    completedAt: '2026-11-15T00:00:01Z',
    durationMs: 1,
    rowCount: 1,
    truncated: false,
    dataVersions: {},
    error: null,
  };
}

it('retains metadata browsing with no manual synchronization actions', async () => {
  vi.mocked(listDatasets).mockResolvedValue([
    { name: 'eod', label: 'EOD', partitionKeys: [], partitionCount: 0 },
  ]);
  vi.mocked(getDatasetPartitionPage).mockResolvedValue({
    items: [],
    offset: 0,
    limit: 500,
    total: 0,
  });
  render(<DatasetExplorer onSelect={vi.fn()} />);
  expect(
    await screen.findByRole('button', { name: /EOD/ })
  ).toBeInTheDocument();
  expect(
    await screen.findByText('No metadata partition found.')
  ).toBeInTheDocument();
  expect(getDatasetPartitionPage).toHaveBeenCalledWith(
    'eod',
    0,
    expect.any(AbortSignal)
  );
  expect(
    screen.queryByRole('button', { name: /sync/i })
  ).not.toBeInTheDocument();
  expect(
    screen.queryByRole('textbox', { name: /reason/i })
  ).not.toBeInTheDocument();
});

it('loads later catalog partitions and pins previews to the selected logical version', async () => {
  catalog();
  vi.mocked(getDatasetPartitionPage)
    .mockResolvedValueOnce({
      items: [partition('A'), partition('B')],
      offset: 0,
      limit: 500,
      total: 3,
    })
    .mockResolvedValueOnce({
      items: [partition('C')],
      offset: 2,
      limit: 500,
      total: 3,
    });
  vi.mocked(submitQuery).mockResolvedValue('query-one');
  vi.mocked(waitForQuery).mockResolvedValue(succeeded());
  vi.mocked(getJsonResult).mockResolvedValue({
    columns: ['close'],
    rows: [{ close: 123 }],
    rowCount: 1,
    truncated: false,
    dataVersions: { eod: 'version-C' },
  });
  const onSelect = vi.fn(),
    onOpenWork = vi.fn();
  render(<DatasetExplorer onSelect={onSelect} onOpenWork={onOpenWork} />);
  await screen.findByText('Loaded 2 of 3 catalog partitions');
  expect(screen.getByText('version-A')).toBeInTheDocument();
  expect(screen.getByText('execution-source')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Load more partitions' }));
  await screen.findByText('Loaded 3 of 3 catalog partitions');
  expect(getDatasetPartitionPage).toHaveBeenLastCalledWith(
    'eod',
    2,
    expect.any(AbortSignal)
  );
  fireEvent.change(screen.getByLabelText('Symbol'), { target: { value: 'C' } });
  expect(onSelect).toHaveBeenLastCalledWith(partition('C'));
  fireEvent.click(screen.getByRole('button', { name: 'Preview' }));
  await waitFor(() =>
    expect(submitQuery).toHaveBeenCalledWith(
      expect.any(String),
      [
        {
          dataset: 'eod',
          partition: { symbol: 'C' },
          dataVersion: 'version-C',
        },
      ],
      100
    )
  );
  await screen.findByText('123');
  fireEvent.click(
    screen.getByRole('button', { name: 'Inspect work readiness' })
  );
  expect(onOpenWork).toHaveBeenCalledTimes(1);
});

it('distinguishes an unavailable catalog from empty metadata', async () => {
  catalog();
  vi.mocked(getDatasetPartitionPage).mockRejectedValue(
    new Error('Catalog unavailable')
  );
  render(<DatasetExplorer onSelect={vi.fn()} />);
  await screen.findByText('Catalog unavailable');
  expect(
    screen.queryByText('No metadata partition found.')
  ).not.toBeInTheDocument();
});

it('keeps catalog controls usable when a partition filter returns to All', async () => {
  catalog();
  vi.mocked(getDatasetPartitionPage).mockResolvedValue({
    items: [partition('A'), partition('B')],
    offset: 0,
    limit: 500,
    total: 2,
  });
  const onSelect = vi.fn();
  render(<DatasetExplorer onSelect={onSelect} />);
  await screen.findByText('version-A');
  fireEvent.change(screen.getByLabelText('Symbol'), { target: { value: 'B' } });
  expect(onSelect).toHaveBeenLastCalledWith(partition('B'));
  fireEvent.change(screen.getByLabelText('Symbol'), { target: { value: '' } });
  expect(screen.getByLabelText('Symbol')).toBeInTheDocument();
  expect(
    screen.queryByText('No metadata partition found.')
  ).not.toBeInTheDocument();
  expect(onSelect).toHaveBeenLastCalledWith(partition('A'));
  fireEvent.change(screen.getByLabelText('Symbol'), { target: { value: 'B' } });
  expect(onSelect).toHaveBeenLastCalledWith(partition('B'));
});

it('ignores a late partition response after changing datasets and clears the previous selection', async () => {
  catalog();
  let resolveFirst!: (page: DatasetPartitionPage) => void;
  vi.mocked(getDatasetPartitionPage)
    .mockReturnValueOnce(
      new Promise((resolve) => {
        resolveFirst = resolve;
      })
    )
    .mockResolvedValueOnce({
      items: [partition('I', 'indicators')],
      offset: 0,
      limit: 500,
      total: 1,
    });
  const onSelect = vi.fn();
  render(<DatasetExplorer onSelect={onSelect} />);
  await waitFor(() => expect(getDatasetPartitionPage).toHaveBeenCalledTimes(1));
  const oldSignal = vi.mocked(getDatasetPartitionPage).mock.calls[0][2];
  fireEvent.click(screen.getByRole('button', { name: /Indicators/ }));
  await screen.findByText('version-I');
  expect(oldSignal?.aborted).toBe(true);
  await act(async () => {
    resolveFirst({ items: [partition('A')], offset: 0, limit: 500, total: 1 });
  });
  expect(screen.queryByText('version-A')).not.toBeInTheDocument();
  expect(onSelect).toHaveBeenCalledWith(null);
  expect(onSelect).toHaveBeenLastCalledWith(partition('I', 'indicators'));
});

it('does not attach an old preview result to a newly selected partition', async () => {
  catalog();
  vi.mocked(getDatasetPartitionPage).mockResolvedValue({
    items: [partition('A'), partition('B')],
    offset: 0,
    limit: 500,
    total: 2,
  });
  vi.mocked(submitQuery).mockResolvedValue('old-query');
  let settle!: (status: QueryStatus) => void;
  vi.mocked(waitForQuery).mockReturnValue(
    new Promise((resolve) => {
      settle = resolve;
    })
  );
  render(<DatasetExplorer onSelect={vi.fn()} />);
  await screen.findByText('version-A');
  fireEvent.click(screen.getByRole('button', { name: 'Preview' }));
  await waitFor(() => expect(waitForQuery).toHaveBeenCalledTimes(1));
  fireEvent.change(screen.getByLabelText('Symbol'), { target: { value: 'B' } });
  await act(async () => {
    settle(succeeded('old-query'));
  });
  expect(getJsonResult).not.toHaveBeenCalled();
  expect(screen.getByText('version-B')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Preview' })).not.toBeDisabled();
});

it('rejects overlapping catalog pages instead of silently mixing observations', async () => {
  catalog();
  vi.mocked(getDatasetPartitionPage)
    .mockResolvedValueOnce({
      items: [partition('A')],
      offset: 0,
      limit: 500,
      total: 2,
    })
    .mockResolvedValueOnce({
      items: [partition('A')],
      offset: 1,
      limit: 500,
      total: 2,
    });
  render(<DatasetExplorer onSelect={vi.fn()} />);
  await screen.findByText('version-A');
  fireEvent.click(screen.getByRole('button', { name: 'Load more partitions' }));
  await screen.findByText(
    'Catalog pagination changed. Refresh the catalog before continuing.'
  );
  expect(
    screen.getByText('Loaded 1 of 2 catalog partitions')
  ).toBeInTheDocument();
});
