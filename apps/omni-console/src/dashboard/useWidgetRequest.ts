import { useCallback, useEffect, useState } from 'react';

import { ApiError } from '../api';
import type { WidgetState } from './types';

export type WidgetRequestResult<T> = {
  state: WidgetState<T>;
  refresh: () => void;
};

export function useWidgetRequest<T>(
  request: (signal: AbortSignal) => Promise<T>,
  toState: (data: T) => WidgetState<T>
): WidgetRequestResult<T> {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<WidgetState<T>>({ status: 'loading' });
  const refresh = useCallback(() => setAttempt((value) => value + 1), []);

  useEffect(() => {
    const controller = new AbortController();
    setState({ status: 'loading' });
    request(controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) setState(toState(data));
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        const message =
          error instanceof Error ? error.message : 'Request failed';
        if (error instanceof ApiError && error.status === 503) {
          setState({ status: 'unavailable', message });
        } else {
          setState({ status: 'error', message, retry: refresh });
        }
      });
    return () => controller.abort();
  }, [attempt, request, refresh, toState]);

  return { state, refresh };
}
