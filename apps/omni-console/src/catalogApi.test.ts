import { afterEach, describe, expect, it, vi } from 'vitest';
import { getDatasetPartitionPage } from './api';

afterEach(() => vi.unstubAllGlobals());
describe('partition catalog API', () => {
  it('forwards the next page offset and AbortSignal to the existing Query API', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        new Response(
          JSON.stringify({ items: [], offset: 500, limit: 500, total: 500 })
        )
      );
    vi.stubGlobal('fetch', fetchMock);
    const signal = new AbortController().signal;
    await getDatasetPartitionPage('sector-features', 500, signal);
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining(
        '/v1/datasets/sector-features/partitions?offset=500&limit=500'
      ),
      expect.objectContaining({ signal })
    );
  });
  it('rejects a non-progressing page rather than presenting an incomplete catalog as complete', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          new Response(
            JSON.stringify({ items: [], offset: 0, limit: 500, total: 1000 })
          )
        )
    );
    await expect(getDatasetPartitionPage('eod')).rejects.toThrow(
      'page is unavailable'
    );
  });
});
