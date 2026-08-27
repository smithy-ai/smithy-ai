import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { sessionFixture } from "./dev/sessionFixture";

export default defineConfig({
  plugins: [react(), sessionFixture()],
  build: {
    outDir: "build/dist",
  },
  server: {
    proxy: {
      "/api": "http://localhost:8080",
      "/webhooks": "http://localhost:8080",
    },
  },
});
