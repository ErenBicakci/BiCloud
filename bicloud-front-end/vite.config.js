import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [
    react({
      // .js uzantılı dosyalar da JSX içerebilir — oxc'ye bunu söyle
      include: /\.(js|jsx|ts|tsx)$/,
    }),
  ],
  server: {
    proxy: {
      '/api-cp': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api-cp/, ''),
      },
    },
  },
})
