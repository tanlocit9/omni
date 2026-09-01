import type { ComponentType } from 'react';

export type WidgetSize = 'small' | 'medium' | 'large';

export type WidgetProvenance = {
  effectiveDataDate: string | null;
  generatedAt: string | null;
  dataVersions: Record<string, string>;
};

export type WidgetState<T> =
  | { status: 'loading' }
  | { status: 'ready'; data: T; provenance: WidgetProvenance; stale: false }
  | { status: 'stale'; data: T; provenance: WidgetProvenance; stale: true }
  | { status: 'empty'; message: string; provenance?: WidgetProvenance }
  | { status: 'unavailable'; message: string }
  | { status: 'error'; message: string; retry?: () => void };

export type DatasetWidgetProps = {
  definition: DatasetWidgetDefinition;
};

export type DatasetWidgetDefinition = {
  id: string;
  dataset: string;
  title: string;
  requiredPartitions: readonly string[];
  supportedFilters: readonly string[];
  defaultSize: WidgetSize;
  component: ComponentType<DatasetWidgetProps>;
};
