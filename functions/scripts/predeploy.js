const {spawnSync} = require("node:child_process");
const path = require("node:path");

const functionsDir = path.resolve(__dirname, "..");

function run(script, args) {
  const result = spawnSync(process.execPath, [script, ...args], {
    cwd: functionsDir,
    stdio: "inherit",
  });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
}

run(
  path.join(functionsDir, "node_modules", "eslint", "bin", "eslint.js"),
  ["--ext", ".js,.ts", "."],
);
run(
  path.join(functionsDir, "node_modules", "typescript", "bin", "tsc"),
  ["-p", path.join(functionsDir, "tsconfig.json")],
);
