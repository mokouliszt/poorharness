import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Served by WebViewAssetLoader at https://appassets.androidplatform.net/assets/webui/,
// so every asset URL is relative. es2017 keeps older Android WebViews working.
export default defineConfig({
  base: "./",
  plugins: [react()],
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  build: {
    target: "es2017",
    outDir: "dist",
    assetsInlineLimit: 0,
    chunkSizeWarningLimit: 1200,
  },
});
