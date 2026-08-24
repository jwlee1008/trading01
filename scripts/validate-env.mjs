const errors = [];
const warnings = [];
const env = process.env;
const production = env.NODE_ENV === "production";

const required = (name) => {
  const value = env[name]?.trim();
  if (!value) errors.push(`${name}: 필수 환경변수입니다.`);
  return value;
};
const validUrl = (name, protocols = ["http:", "https:"]) => {
  const value = required(name);
  if (!value) return;
  try {
    const parsed = new URL(value.replace(/^jdbc:/, ""));
    if (!protocols.includes(parsed.protocol)) errors.push(`${name}: 지원하지 않는 프로토콜입니다.`);
  } catch { errors.push(`${name}: 올바른 URL 형식이 아닙니다.`); }
};

const major = Number(process.versions.node.split(".")[0]);
if (major < 22) errors.push(`Node.js 22 이상이 필요합니다. 현재 ${process.versions.node}`);
validUrl("EXPO_PUBLIC_API_URL");
validUrl("EXPO_PUBLIC_SUPABASE_URL");
required("EXPO_PUBLIC_SUPABASE_PUBLISHABLE_KEY");
validUrl("SUPABASE_URL");
required("SUPABASE_ANON_KEY");
required("SUPABASE_SERVICE_ROLE_KEY");
validUrl("DATABASE_URL", ["postgresql:"]);
required("DATABASE_USERNAME");
required("DATABASE_PASSWORD");
required("MARKET_CALENDAR_VERSION");

const mode = (env.KIWOOM_MODE || "real").trim();
if (!new Set(["real", "demo"]).has(mode)) errors.push("KIWOOM_MODE: real 또는 demo만 허용됩니다.");
const keyName = mode === "demo" ? "KIWOOM_APP_KEY_DEMO" : "KIWOOM_APP_KEY";
const secretName = mode === "demo" ? "KIWOOM_APP_SECRET_DEMO" : "KIWOOM_APP_SECRET";
if ((env.MARKET_DATA_PROVIDER || "").toLowerCase() === "kiwoom") {
  required(keyName);
  required(secretName);
}

if (production) {
  const token = required("WORKER_SERVICE_TOKEN");
  if (token && (token.length < 32 || token.includes("local-worker"))) errors.push("WORKER_SERVICE_TOKEN: 운영에서는 32자 이상의 임의값을 사용하세요.");
  if (mode === "demo") warnings.push("운영 환경에서 KIWOOM_MODE=demo가 설정되어 있습니다.");
  if ((env.EXPO_PUBLIC_API_URL || "").includes("localhost")) errors.push("EXPO_PUBLIC_API_URL: 운영에서 localhost를 사용할 수 없습니다.");
}

if ((env.WORKER_ENABLED || "false") !== "true") warnings.push("WORKER_ENABLED=false: 예약 작업이 실행되지 않습니다.");
if ((env.BACKFILL_DRY_RUN || "false") === "true") warnings.push("BACKFILL_DRY_RUN=true: 수집 결과가 DB에 저장되지 않습니다.");

for (const warning of warnings) console.warn(`경고: ${warning}`);
if (errors.length) {
  for (const error of errors) console.error(`오류: ${error}`);
  process.exitCode = 1;
} else {
  console.log(`환경 검증 통과 (${production ? "production" : "development"}, kiwoom=${mode})`);
}
