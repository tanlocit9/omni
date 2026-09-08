export type IchimokuSignal = {
  code: string;
  signalDate: string;
  signal: 'BULLISH' | 'NEUTRAL' | 'BEARISH';
  price: number;
  score: number;
  reasonCodes: string[];
};

export type IchimokuSignalsResponse = {
  effectiveDataDate: string;
  generatedAt: string;
  dataVersions: Record<string, string>;
  truncated: boolean;
  exchange: 'HOSE' | 'HNX' | 'UPCOM';
  limit: number;
  signals: IchimokuSignal[];
};

export type SignalStrategy =
  | 'TREND_MOMENTUM_V1'
  | 'ICHIMOKU_V1'
  | 'CONFIRMED_TREND_EQUALS';

export type SignalComponent = {
  strategy: SignalStrategy;
  signal: 'BULLISH' | 'NEUTRAL' | 'BEARISH' | 'NO_DECISION';
  mappedValue: number | null;
  score: number;
  signalDate: string | null;
  reasonCodes: string[];
};

export type SignalHistoryRow = {
  code: string;
  signalDate: string;
  signal: 'BULLISH' | 'NEUTRAL' | 'BEARISH';
  price: number;
  score: number;
  reasonCodes: string[];
  modelVersion: string | null;
  components: SignalComponent[] | null;
  actualReturnT5: number | null;
  actualReturnT10: number | null;
  actualReturnT15: number | null;
  actualReturnT20: number | null;
};

export type SignalHistoryResponse = {
  effectiveDataDate: string;
  generatedAt: string;
  dataVersions: Record<string, string>;
  truncated: boolean;
  exchange: 'HOSE' | 'HNX' | 'UPCOM';
  strategy: SignalStrategy;
  availableExchanges: Array<'HOSE' | 'HNX' | 'UPCOM'>;
  symbol: string | null;
  limit: number;
  history: SignalHistoryRow[];
};
