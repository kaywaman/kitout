import { defineConfig } from "vite";

// JSX is compiled by Vite itself (tsconfig "jsx": "react-jsx"), so no framework plugin is needed.
// Add @vitejs/plugin-react if you want React Fast Refresh in dev mode (see docs/MAINTAINING.md).
export default defineConfig({
  server: {
    // IPv4 explicitly: "localhost" can resolve to ::1 in Node but 127.0.0.1 in Java,
    // and then Quinoa never sees the port open.
    host: "127.0.0.1",
    port: 5173,
    strictPort: true,
  },
  build: {
    outDir: "dist",
    sourcemap: true,
    target: "es2022",
  },
});
