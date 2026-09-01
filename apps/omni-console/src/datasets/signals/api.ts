import { queryServiceRequest } from '../../api';
import type { IchimokuSignalsResponse, SignalHistoryResponse } from './types';

export function getIchimokuSignals(
  signal: AbortSignal,
  exchange: string,
  limit: number
): Promise<IchimokuSignalsResponse> {
  const query = new URLSearchParams({ exchange, limit: String(limit) });
  return queryServiceRequest(`/v1/dashboard/ichimoku-signals?${query}`, {
    signal,
  });
}

export function getSignalHistory(
  signal: AbortSignal,
  exchange: string | null,
  symbol: string,
  limit: number
): Promise<SignalHistoryResponse> {
  const query = new URLSearchParams({ limit: String(limit) });
  if (exchange) query.set('exchange', exchange);
  if (symbol) query.set('symbol', symbol);
  return queryServiceRequest(`/v1/dashboard/signal-history?${query}`, {
    signal,
  });
}
