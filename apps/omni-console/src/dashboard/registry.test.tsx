import type { ComponentType } from 'react';
import { describe, expect, it } from 'vitest';

import { createWidgetRegistry, requireWidget } from './registry';
import type { DatasetWidgetDefinition, DatasetWidgetProps } from './types';

const TestWidget: ComponentType<DatasetWidgetProps> = () => null;

function definition(id: string): DatasetWidgetDefinition {
  return {
    id,
    dataset: 'test',
    title: id,
    requiredPartitions: [],
    supportedFilters: [],
    defaultSize: 'small',
    component: TestWidget,
  };
}

describe('dashboard widget registry', () => {
  it('constructs an allowlisted registry', () => {
    const registry = createWidgetRegistry([
      definition('test.one'),
      definition('test.two'),
    ]);

    expect([...registry.keys()]).toEqual(['test.one', 'test.two']);
  });

  it('rejects duplicate widget IDs', () => {
    expect(() =>
      createWidgetRegistry([definition('test.one'), definition('test.one')])
    ).toThrow('Duplicate dashboard widget ID: test.one');
  });

  it('rejects unknown registered widget lookups', () => {
    expect(() => requireWidget('unknown.widget')).toThrow(
      'Unknown dashboard widget ID: unknown.widget'
    );
  });
});
