import { queryServiceRequest } from '../../api';
import type { MarketBreadthResponse, TopMoversResponse } from './types';

const DEFAULT_EXCHANGE = 'HOSE';

export function getMarketBreadth(
  signal: AbortSignal,
  exchange = DEFAULT_EXCHANGE
): Promise<MarketBreadthResponse> {
  const query = new URLSearchParams({ exchange });
  return queryServiceRequest(`/v1/dashboard/market-breadth?${query}`, {
    signal,
  });
}

export function getTopMovers(
  signal: AbortSignal,
  exchange = DEFAULT_EXCHANGE,
  limit = 10
): Promise<TopMoversResponse> {
  const query = new URLSearchParams({ exchange, limit: String(limit) });
  return queryServiceRequest(`/v1/dashboard/top-movers?${query}`, { signal });
}
