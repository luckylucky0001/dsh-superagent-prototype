import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';

const backend = process.env.ORCA_API ?? 'http://localhost:18080';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    proxy: {
      // REST and the SSE stream (/api/stream); nothing else is proxied.
      '/api': {
        target: backend,
        changeOrigin: false,
        configure: (proxy) => {
          // keep server-sent events unbuffered
          proxy.on('proxyRes', (res) => {
            if (String(res.headers['content-type'] ?? '').startsWith('text/event-stream')) {
              res.headers['cache-control'] = 'no-cache';
              res.headers['x-accel-buffering'] = 'no';
            }
          });
        },
      },
    },
  },
  preview: {
    port: 4173,
    proxy: { '/api': { target: backend } },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 2500,
    rollupOptions: {
      output: {
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom', '@tanstack/react-query'],
          antd: ['antd', '@ant-design/icons'],
          flow: ['@xyflow/react'],
          markdown: ['react-markdown', 'remark-gfm'],
        },
      },
    },
  },
});
