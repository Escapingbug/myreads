import type { CapacitorConfig } from "@capacitor/cli";
const config: CapacitorConfig = {
  appId: "io.myreads.app",
  appName: "纸间",
  webDir: "dist",
  android: { allowMixedContent: false },
  // Keep ordinary fetch local. Direct CapacitorHttp.request still uses native HTTP.
  plugins: { CapacitorHttp: { enabled: false } },
};
export default config;
