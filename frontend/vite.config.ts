import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * A que backend apunta el proxy. Por defecto el de siempre.
 *
 * Es configurable para poder levantar DOS instancias a la vez --una por rama, en
 * puertos distintos-- y comparar el mismo flujo en las dos sin tocar este archivo:
 * `set BACKEND=http://localhost:8081` y `npm run dev -- --port 5174`.
 */
export default defineConfig(({ mode }) => {
  const BACKEND = loadEnv(mode, process.cwd(), '').BACKEND ?? 'http://localhost:8080'
  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        '/api': BACKEND,
        '/ws':  { target: BACKEND, ws: true },
      },
    },
    define: { global: 'window' },   // sockjs-client espera `global`
  }
})
