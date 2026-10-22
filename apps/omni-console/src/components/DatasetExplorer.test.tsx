import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { listDatasets, listPartitions } from '../api';
import { DatasetExplorer } from './DatasetExplorer';

vi.mock('../api', async (loadOriginal) => ({
  ...(await loadOriginal<typeof import('../api')>()),
  listDatasets: vi.fn(),
  listPartitions: vi.fn(),
}));

afterEach(() => cleanup());

it('retains metadata browsing with no manual synchronization actions', async () => {
  vi.mocked(listDatasets).mockResolvedValue([
    { name: 'eod', label: 'EOD', partitionKeys: [], partitionCount: 0 },
  ]);
  vi.mocked(listPartitions).mockResolvedValue([]);
  render(<DatasetExplorer onSelect={vi.fn()} />);
  expect(
    await screen.findByRole('button', { name: /EOD/ })
  ).toBeInTheDocument();
  expect(
    await screen.findByText('No metadata partition found.')
  ).toBeInTheDocument();
  expect(listPartitions).toHaveBeenCalledWith('eod');
  expect(
    screen.queryByRole('button', { name: /sync/i })
  ).not.toBeInTheDocument();
  expect(
    screen.queryByRole('textbox', { name: /reason/i })
  ).not.toBeInTheDocument();
});
