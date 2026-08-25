import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { App } from './App';

vi.stubGlobal(
  'fetch',
  vi.fn(() => Promise.resolve({ ok: true, json: () => Promise.resolve([]) }))
);

describe('App', () => {
  it('renders the locked Omni Console navigation', () => {
    render(<App />);

    expect(
      screen.getByRole('heading', { name: 'Omni Console' })
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Dataset Explorer' })
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'SQL Console' })
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Dashboard' })
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Jobs' })).toBeInTheDocument();
  });
});
