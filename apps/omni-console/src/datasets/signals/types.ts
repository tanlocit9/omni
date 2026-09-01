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

export type SignalHistoryRow = {
  code: string;
  signalDate: string;
  signal: 'BULLISH' | 'NEUTRAL' | 'BEARISH';
  price: number;
  score: number;
  reasonCodes: string[];
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
  availableExchanges: Array<'HOSE' | 'HNX' | 'UPCOM'>;
  symbol: string | null;
  limit: number;
  history: SignalHistoryRow[];
};
