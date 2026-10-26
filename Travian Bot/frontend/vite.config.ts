import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5185,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8185',
        changeOrigin: true,
      },
    },
  },
});
