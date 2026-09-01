import type { PropsWithChildren, ReactNode } from 'react';

import type { DatasetWidgetDefinition, WidgetProvenance } from '../types';

type WidgetFrameProps = PropsWithChildren<{
  definition: DatasetWidgetDefinition;
  status: 'loading' | 'ready' | 'stale' | 'empty' | 'unavailable' | 'error';
  description?: ReactNode;
  provenance?: WidgetProvenance;
  onRefresh?: () => void;
  refreshDisabled?: boolean;
}>;

export function WidgetFrame({
  definition,
  status,
  description,
  provenance,
  onRefresh,
  refreshDisabled = false,
  children,
}: WidgetFrameProps) {
  return (
    <article
      className={`widget-placeholder widget-size-${definition.defaultSize}`}
      aria-labelledby={`${definition.id}-title`}
      data-widget-id={definition.id}
    >
      <div className="widget-heading">
        <span>{definition.dataset}</span>
        <div className="widget-heading-actions">
          <button
            type="button"
            className="widget-refresh"
            aria-label={`Refresh ${definition.title}`}
            onClick={onRefresh}
            disabled={!onRefresh || refreshDisabled}
          >
            Refresh
          </button>
          <span className={`widget-status widget-status-${status}`}>
            {status}
          </span>
        </div>
      </div>
      <h3 id={`${definition.id}-title`}>{definition.title}</h3>
      {description && <p>{description}</p>}
      {children}
      {provenance && <WidgetProvenanceLine provenance={provenance} />}
    </article>
  );
}

export function WidgetProvenanceLine({
  provenance,
}: {
  provenance: WidgetProvenance;
}) {
  const versionCount = Object.keys(provenance.dataVersions).length;
  return (
    <dl className="widget-provenance" aria-label="Data provenance">
      <div>
        <dt>Effective date</dt>
        <dd>{provenance.effectiveDataDate ?? 'Not reported'}</dd>
      </div>
      <div>
        <dt>Sources</dt>
        <dd>{versionCount || 'Not reported'}</dd>
      </div>
    </dl>
  );
}
