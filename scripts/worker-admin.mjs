const [action = "state", value] = process.argv.slice(2);
const baseUrl = (process.env.EXPO_PUBLIC_API_URL || "http://127.0.0.1:3000").replace(/\/$/, "");
const token = process.env.WORKER_SERVICE_TOKEN?.trim();
if (!token) throw new Error("WORKER_SERVICE_TOKEN이 필요합니다.");

let path = "/v1/internal/worker/state";
let method = "GET";
if (action === "run") {
  if (!value) throw new Error("사용법: pnpm worker:admin run <task-name>");
  path = `/v1/internal/worker/tasks/${encodeURIComponent(value)}`;
  method = "POST";
} else if (action === "retry") {
  if (!value) throw new Error("사용법: pnpm worker:admin retry <run-id>");
  path = `/v1/internal/worker/runs/${encodeURIComponent(value)}/retry`;
  method = "POST";
} else if (action !== "state") {
  throw new Error("action은 state, run, retry 중 하나여야 합니다.");
}

const response = await fetch(`${baseUrl}${path}`, { method, headers: { "x-worker-service-token": token } });
const body = await response.json().catch(() => ({}));
if (!response.ok) throw new Error(body.message || `Worker API 요청 실패 (${response.status})`);
console.log(JSON.stringify(body.data, null, 2));
