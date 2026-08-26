import react from '@vitejs/plugin-react';
import { loadEnv } from 'vite';
import { defineConfig } from 'vitest/config';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const platformTarget =
    env.OMNI_CONSOLE_PLATFORM_PROXY_TARGET ?? 'http://localhost:8080';
  const systemOperatorUuid =
    env.SYSTEM_OPERATOR_UUID ?? 'b252fe62-80f3-4df9-9734-5dc549705a25';

  return {
    plugins: [react()],
    define: {
      'import.meta.env.SYSTEM_OPERATOR_UUID':
        JSON.stringify(systemOperatorUuid),
    },
    server: {
      proxy: {
        '/api/platform': {
          target: platformTarget,
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/api\/platform/, ''),
          configure: (proxy) => {
            proxy.on('proxyReq', (proxyRequest) => {
              proxyRequest.removeHeader('X-Omni-User');
              proxyRequest.setHeader('X-Omni-User', systemOperatorUuid);
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
