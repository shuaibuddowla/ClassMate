import { build } from "esbuild";
import nextEnv from "@next/env";
const { loadEnvConfig } = nextEnv;
loadEnvConfig(process.cwd());
const config = process.env.NEXT_PUBLIC_FIREBASE_CONFIG || "null";
JSON.parse(config);
await build({
  entryPoints: ["src/worker.ts"],
  outfile: "public/sw.js",
  bundle: true,
  format: "iife",
  platform: "browser",
  target: "es2022",
  minify: true,
  define: {
    __FIREBASE_CONFIG__: config,
    __SHELL_VERSION__: JSON.stringify(Date.now().toString()),
  },
});
