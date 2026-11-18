/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In development, /api is proxied to core banking so the browser sees one origin (as it does in
// production behind nginx / IIS). Set MFI_CORE_URL to point elsewhere.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: { "/api": process.env.MFI_CORE_URL ?? "http://localhost:8080" },
  },
  build: { sourcemap: true },
  test: {
    environment: "jsdom",
    globals: true,
    restoreMocks: true,
  },
});
