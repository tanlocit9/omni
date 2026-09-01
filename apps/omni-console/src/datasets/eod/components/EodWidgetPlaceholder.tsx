import { WidgetStateView } from '../../../dashboard/shared/WidgetStateView';
import type { DatasetWidgetProps } from '../../../dashboard/types';

export function EodWidgetPlaceholder({ definition }: DatasetWidgetProps) {
  return (
    <WidgetStateView
      definition={definition}
      state={{
        status: 'unavailable',
        message: 'Awaiting a bounded EOD market summary contract.',
      }}
      renderReady={() => null}
    />
  );
}
