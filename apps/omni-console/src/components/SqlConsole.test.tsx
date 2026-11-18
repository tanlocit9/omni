import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import {
  getArrowResult,
  submitQuery,
  waitForQuery,
  type DatasetManifest,
  type QueryStatus,
} from '../api';
import { SqlConsole } from './SqlConsole';

vi.mock('@monaco-editor/react', () => ({
  default: ({
    value,
    onChange,
  }: {
    value: string;
    onChange: (value: string) => void;
  }) => (
    <textarea
      aria-label="SQL editor"
      value={value}
      onChange={(event) => onChange(event.target.value)}
    />
  ),
}));
vi.mock('../api', async (loadOriginal) => ({
  ...(await loadOriginal<typeof import('../api')>()),
  submitQuery: vi.fn(),
  waitForQuery: vi.fn(),
  getArrowResult: vi.fn(),
}));
beforeEach(() => {
  vi.resetAllMocks();
  localStorage.clear();
});

const manifest: DatasetManifest = {
  dataset: 'eod',
  partition: { symbol: 'VCB' },
  status: 'READY',
  dataVersion: `sha256:${'a'.repeat(64)}`,
  rowCount: 1,
  columnCount: 1,
  totalBytes: 10,
  columns: [{ name: 'close', type: 'DOUBLE', nullable: false }],
  generatedAt: '2026-11-15T00:00:00Z',
  minTimestamp: null,
  maxTimestamp: null,
  schemaVersion: 1,
  schemaHash: 'schema-one',
  objectCount: 1,
  inputs: [],
  sourceExecutionId: 'source-execution',
};

it('requires a catalog selection without claiming an entire dataset is work READY', async () => {
  render(<SqlConsole manifest={null} />);
  fireEvent.click(screen.getByRole('button', { name: 'Run query' }));
  expect(
    await screen.findByText('Select a catalog partition in Explorer first.')
  ).toBeInTheDocument();
  expect(submitQuery).not.toHaveBeenCalled();
});

it('pins the selected version and exposes the complete accepted query version', async () => {
  vi.mocked(submitQuery).mockResolvedValue('query-one');
  vi.mocked(waitForQuery).mockResolvedValue({
    queryId: 'query-one',
    state: 'SUCCEEDED',
    durationMs: 10,
    rowCount: 1,
    createdAt: '2026-11-15T00:00:00Z',
    startedAt: '2026-11-15T00:00:00Z',
    completedAt: '2026-11-15T00:00:01Z',
    truncated: false,
    dataVersions: { eod: manifest.dataVersion },
    error: null,
  } satisfies QueryStatus);
  vi.mocked(getArrowResult).mockResolvedValue({
    columns: ['close'],
    rows: [{ close: 123 }],
    rowCount: 1,
    truncated: false,
    dataVersions: { eod: manifest.dataVersion },
  });
  render(<SqlConsole manifest={manifest} />);
  fireEvent.click(screen.getByRole('button', { name: 'Run query' }));
  await screen.findByText('123');
  expect(submitQuery).toHaveBeenCalledWith(
    expect.any(String),
    [
      {
        dataset: 'eod',
        partition: { symbol: 'VCB' },
        dataVersion: manifest.dataVersion,
      },
    ],
    5000
  );
  const versions = screen
    .getByText('Accepted query data versions')
    .closest('details')!;
  expect(within(versions).getByText(manifest.dataVersion)).toBeInTheDocument();
  expect(screen.getByText('source-execution')).toBeInTheDocument();
});
