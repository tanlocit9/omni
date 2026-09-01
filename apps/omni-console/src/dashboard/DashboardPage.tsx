import { requireWidget } from './registry';

const dashboardWidgetIds = [
  'eod.market-breadth',
  'eod.top-movers',
  'sector-features.heatmap',
  'sector-features.ranking',
  'signals.history',
  'signals.ichimoku',
] as const;

function RegisteredWidget({ id }: { id: string }) {
  const definition = requireWidget(id);
  const Component = definition.component;
  return <Component definition={definition} />;
}

export function DashboardPage() {
  return (
    <main className="dashboard-page" aria-labelledby="dashboard-title">
      <section className="dashboard-hero">
        <div>
          <p className="eyebrow">Market intelligence</p>
          <h2 id="dashboard-title">Market Dashboard</h2>
          <p className="dashboard-intro">
            A fixed, dataset-owned view of market context. Widgets appear as
            their bounded Query Service contracts are delivered.
          </p>
        </div>
        <div className="dashboard-scope" aria-label="Dashboard data scope">
          <span>Daily market data</span>
          <strong>Effective date pending</strong>
        </div>
      </section>

      <section className="freshness-widget" aria-label="Dashboard freshness">
        <RegisteredWidget id="metadata.freshness" />
      </section>

      <section className="dashboard-grid" aria-label="Market widgets">
        {dashboardWidgetIds.map((id) => (
          <RegisteredWidget id={id} key={id} />
        ))}
      </section>
    </main>
  );
}
