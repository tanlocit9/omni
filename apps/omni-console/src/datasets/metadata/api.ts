import { queryServiceRequest } from '../../api';
import type { FreshnessResponse } from './types';

export function getDashboardFreshness(
  signal: AbortSignal
): Promise<FreshnessResponse> {
  return queryServiceRequest('/v1/dashboard/freshness', { signal });
}
