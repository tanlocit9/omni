import { WidgetStateView } from '../../../dashboard/shared/WidgetStateView';
import type { DatasetWidgetProps } from '../../../dashboard/types';

export function SectorWidgetPlaceholder({ definition }: DatasetWidgetProps) {
  return (
    <WidgetStateView
      definition={definition}
      state={{
        status: 'unavailable',
        message: 'Awaiting a supported sector-feature source contract.',
      }}
      renderReady={() => null}
    />
  );
}
