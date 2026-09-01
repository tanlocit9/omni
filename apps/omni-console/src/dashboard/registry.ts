import { MarketBreadth } from '../datasets/eod/components/MarketBreadth';
import { TopMovers } from '../datasets/eod/components/TopMovers';
import { DataFreshnessBanner } from '../datasets/metadata/components/DataFreshnessBanner';
import { SectorWidgetPlaceholder } from '../datasets/sector-features/components/SectorWidgetPlaceholder';
import { LatestSignalFeed } from '../datasets/signals/components/LatestSignalFeed';
import { SignalHistory } from '../datasets/signals/components/SignalHistory';
import type { DatasetWidgetDefinition } from './types';

export const widgetDefinitions = [
  {
    id: 'metadata.freshness',
    dataset: 'metadata',
    title: 'Data freshness',
    requiredPartitions: [],
    supportedFilters: [],
    defaultSize: 'large',
    component: DataFreshnessBanner,
  },
  {
    id: 'eod.market-breadth',
    dataset: 'eod',
    title: 'Market breadth',
    requiredPartitions: ['exchange'],
    supportedFilters: ['date', 'exchange'],
    defaultSize: 'medium',
    component: MarketBreadth,
  },
  {
    id: 'eod.top-movers',
    dataset: 'eod',
    title: 'Top movers',
    requiredPartitions: ['exchange'],
    supportedFilters: ['date', 'exchange', 'limit'],
    defaultSize: 'medium',
    component: TopMovers,
  },
  {
    id: 'sector-features.heatmap',
    dataset: 'sector-features',
    title: 'Sector heatmap',
    requiredPartitions: ['timeframe', 'sectorLevel'],
    supportedFilters: ['date', 'timeframe', 'sectorLevel'],
    defaultSize: 'large',
    component: SectorWidgetPlaceholder,
  },
  {
    id: 'sector-features.ranking',
    dataset: 'sector-features',
    title: 'Sector ranking',
    requiredPartitions: ['timeframe', 'sectorLevel'],
    supportedFilters: ['date', 'timeframe', 'sectorLevel'],
    defaultSize: 'medium',
    component: SectorWidgetPlaceholder,
  },
  {
    id: 'signals.history',
    dataset: 'signals',
    title: 'Signal history',
    requiredPartitions: ['strategy', 'timeframe', 'exchange'],
    supportedFilters: ['exchange', 'symbol', 'limit'],
    defaultSize: 'large',
    component: SignalHistory,
  },
  {
    id: 'signals.ichimoku',
    dataset: 'signals',
    title: 'Ichimoku signals',
    requiredPartitions: ['strategy', 'timeframe', 'exchange'],
    supportedFilters: ['date', 'exchange', 'limit'],
    defaultSize: 'large',
    component: LatestSignalFeed,
  },
] as const satisfies readonly DatasetWidgetDefinition[];

export function createWidgetRegistry(
  definitions: readonly DatasetWidgetDefinition[]
): ReadonlyMap<string, DatasetWidgetDefinition> {
  const registry = new Map<string, DatasetWidgetDefinition>();
  for (const definition of definitions) {
    if (registry.has(definition.id)) {
      throw new Error(`Duplicate dashboard widget ID: ${definition.id}`);
    }
    registry.set(definition.id, definition);
  }
  return registry;
}

export const widgetRegistry = createWidgetRegistry(widgetDefinitions);

export function requireWidget(id: string): DatasetWidgetDefinition {
  const definition = widgetRegistry.get(id);
  if (!definition) throw new Error(`Unknown dashboard widget ID: ${id}`);
  return definition;
}
