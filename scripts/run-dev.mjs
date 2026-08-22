import { spawn } from "node:child_process";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const pnpm = process.platform === "win32" ? "pnpm.cmd" : "pnpm";
const children = [
  spawn(process.execPath, ["--env-file=.env", "scripts/run-backend.mjs", "api", "bootRun"], { cwd: root, stdio: "inherit" }),
  spawn(process.execPath, ["--env-file=.env", "scripts/run-backend.mjs", "worker", "bootRun"], { cwd: root, stdio: "inherit" }),
  spawn(pnpm, ["dev:mobile"], { cwd: root, stdio: "inherit" }),
];

let stopping = false;
const stop = (signal = "SIGTERM") => {
  if (stopping) return;
  stopping = true;
  for (const child of children) if (!child.killed) child.kill(signal);
};
process.on("SIGINT", () => stop("SIGINT"));
process.on("SIGTERM", () => stop("SIGTERM"));
for (const child of children) child.on("exit", (code) => {
  if (!stopping && code && code !== 0) {
    process.exitCode = code;
    stop();
  }
});
