import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { sourceProxy } from "./dev-proxy.ts";
export default defineConfig(({ mode }) => ({
  plugins: [
    vue(),
    sourceProxy(),
    ...(mode === "native-verify"
      ? [
          {
            name: "native-verification-entry",
            transformIndexHtml: {
              order: "pre" as const,
              handler: (html: string) =>
                html.replace("/src/main.ts", "/tests/native-verify.ts"),
            },
          },
        ]
      : []),
  ],
  server: { host: "127.0.0.1" },
}));
