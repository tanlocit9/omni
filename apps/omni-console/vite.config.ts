import react from '@vitejs/plugin-react';
import { loadEnv } from 'vite';
import { defineConfig } from 'vitest/config';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const platformTarget =
    env.OMNI_CONSOLE_PLATFORM_PROXY_TARGET ?? 'http://localhost:8080';
  const localOperator =
    env.OMNI_CONSOLE_LOCAL_OPERATOR ?? 'local-console-operator';

  return {
    plugins: [react()],
    server: {
      proxy: {
        '/api/platform': {
          target: platformTarget,
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/api\/platform/, ''),
          configure: (proxy) => {
            proxy.on('proxyReq', (proxyRequest) => {
              proxyRequest.removeHeader('X-Omni-User');
              proxyRequest.setHeader('X-Omni-User', localOperator);
            });
          },
        },
      },
    },
    test: {
      environment: 'jsdom',
      setupFiles: './src/test/setup.ts',
    },
  };
});
