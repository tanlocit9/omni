import type { QueryResult } from '../api';

interface Props {
  result: QueryResult | null;
}

function displayValue(value: unknown): string {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

export function ResultTable({ result }: Props) {
  if (!result)
    return <div className="empty-state">Run a query to inspect rows.</div>;
  return (
    <div className="result-wrap">
      <div className="result-meta">
        {result.rowCount.toLocaleString()} rows
        {result.truncated ? ' · limited' : ''}
      </div>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              {result.columns.map((column) => (
                <th key={column}>{column}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {result.rows.map((row, index) => (
              <tr key={index}>
                {result.columns.map((column) => (
                  <td key={column}>{displayValue(row[column])}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
