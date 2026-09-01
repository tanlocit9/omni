export type DashboardProvenanceResponse = {
  effectiveDataDate: string;
  generatedAt: string;
  dataVersions: Record<string, string>;
  truncated: boolean;
};

export type MarketBreadthResponse = DashboardProvenanceResponse & {
  metrics: {
    advancing: number;
    declining: number;
    unchanged: number;
    total: number;
  };
};

export type TopMover = {
  code: string;
  close: number;
  previousClose: number;
  changePercent: number;
};

export type TopMoversResponse = DashboardProvenanceResponse & {
  limit: number;
  gainers: TopMover[];
  losers: TopMover[];
};
