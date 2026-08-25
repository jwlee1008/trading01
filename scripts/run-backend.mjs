import { readdirSync } from "node:fs";
import { join, resolve } from "node:path";
import { spawn } from "node:child_process";

const [moduleName, action = "bootRun"] = process.argv.slice(2);
if (!new Set(["api", "worker"]).has(moduleName)) throw new Error("module must be api or worker");

const root = resolve(import.meta.dirname, "..");
const backend = join(root, "apps", "backend");
const env = { ...process.env, GRADLE_USER_HOME: join(backend, ".gradle-user-home") };

if (!env.JAVA_HOME) {
  const bundledRoot = join(backend, ".tooling", "jdk21");
  try {
    const candidates = readdirSync(bundledRoot, { withFileTypes: true }).filter((entry) => entry.isDirectory());
    if (candidates[0]) env.JAVA_HOME = join(bundledRoot, candidates[0].name);
  } catch { /* System JDK remains available through PATH. */ }
}
if (env.JAVA_HOME) env.PATH = `${join(env.JAVA_HOME, "bin")}${process.platform === "win32" ? ";" : ":"}${env.PATH ?? ""}`;

const workerActions = {
  bootRun: [":signal-worker:bootRun"],
  once: [":signal-worker:bootRun"],
  check: [":signal-worker:test", ":signal-worker:build", "--no-daemon"],
  importCalendar: [":signal-worker:bootRun"],
  importInstruments: [":signal-worker:bootRun"],
  refreshKospiTop10: [":signal-worker:bootRun"],
  backfillCandles: [":signal-worker:bootRun"],
  backfillKospiTop10: [":signal-worker:bootRun"],
  prepareMarketData: [":signal-worker:bootRun"],
};
const marketActions = {
  importCalendar: "import-calendar",
  importInstruments: "import-instruments",
  refreshKospiTop10: "refresh-kospi-top10",
  backfillCandles: "backfill-candles",
  backfillKospiTop10: "backfill-kospi-top10",
  prepareMarketData: "prepare",
};

let args;
if (moduleName === "api") {
  if (!new Set(["bootRun", "check"]).has(action)) throw new Error(`Unsupported API action: ${action}`);
  args = action === "check" ? [":signal-api:test", ":signal-api:build", "--no-daemon"] : [":signal-api:bootRun"];
} else {
  args = workerActions[action];
  if (!args) throw new Error(`Unsupported Worker action: ${action}`);
  if (action === "once") env.WORKER_ONCE = "true";
  if (marketActions[action]) env.MARKET_DATA_ACTION = marketActions[action];
}

const executable = process.platform === "win32" ? join(backend, "gradlew.bat") : "sh";
const executableArgs = process.platform === "win32" ? args : ["./gradlew", ...args];
const child = spawn(executable, executableArgs, {
  cwd: backend, env, stdio: "inherit", shell: process.platform === "win32",
  detached: process.platform !== "win32",
});
let stopping = false;
const stop = (signal) => {
  if (stopping || child.exitCode !== null) return;
  stopping = true;
  try {
    if (process.platform === "win32") child.kill(signal);
    else process.kill(-child.pid, signal);
  } catch { /* The Gradle process already exited. */ }
};
process.on("SIGINT", () => stop("SIGINT"));
process.on("SIGTERM", () => stop("SIGTERM"));
child.on("error", (error) => { console.error(error.message); process.exitCode = 1; });
child.on("exit", (code, signal) => { process.exitCode = stopping ? 0 : signal ? 1 : (code ?? 1); });
