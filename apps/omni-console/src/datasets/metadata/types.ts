export type DatasetFreshness = {
  dataset: string;
  status: string;
  generatedAt?: string;
  effectiveDataDate?: string;
  dataVersion?: string;
  partitionCount?: number;
};

export type FreshnessResponse = {
  generatedAt: string;
  datasets: DatasetFreshness[];
};
