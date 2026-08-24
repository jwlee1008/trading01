import { spawn } from "node:child_process";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const pnpm = process.platform === "win32" ? "pnpm.cmd" : "pnpm";
const spawnOptions = { cwd: root, stdio: "inherit" };
const children = [
  spawn(process.execPath, ["--env-file=.env", "scripts/run-backend.mjs", "api", "bootRun"], spawnOptions),
  spawn(process.execPath, ["--env-file=.env", "scripts/run-backend.mjs", "worker", "bootRun"], spawnOptions),
  spawn(pnpm, ["dev:mobile"], spawnOptions),
];

let stopping = false;
const stop = (signal = "SIGTERM") => {
  if (stopping) return;
  stopping = true;
  for (const child of children) {
    if (child.exitCode !== null) continue;
    try { child.kill(signal); } catch { /* Child already exited. */ }
  }
};
process.on("SIGINT", () => stop("SIGINT"));
process.on("SIGTERM", () => stop("SIGTERM"));
for (const child of children) child.on("exit", (code) => {
  if (!stopping && code && code !== 0) {
    process.exitCode = code;
    stop();
  }
});
