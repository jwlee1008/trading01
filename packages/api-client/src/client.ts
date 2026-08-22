import type {
  AlertSettingsInput,
  CreateStrategyInput,
  ManualExecutionInput,
  PaperOrderInput,
  ProfileVisibilityInput,
  RankingPeriod,
  SellRuleInput,
} from "./schemas.js";

export interface ApiEnvelope<T> {
  data: T;
  meta: { requestId: string; generatedAt: string; dataSource: "postgres" | "unavailable" };
}

export interface ProviderStatus {
  provider: string;
  state: "CONNECTED" | "DEGRADED" | "DISCONNECTED";
  lastCandleAt: string | null;
  delayed: boolean;
}

export interface SignalApiClientOptions {
  timeoutMs?: number;
}

export const DEFAULT_API_TIMEOUT_MS = 10_000;

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly details?: unknown,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export class SignalApiClient {
  private readonly timeoutMs: number;

  constructor(
    private readonly baseUrl: string,
    private readonly fetcher: typeof fetch = fetch,
    private readonly accessToken?: string,
    options: SignalApiClientOptions = {},
  ) {
    const timeoutMs = options.timeoutMs ?? DEFAULT_API_TIMEOUT_MS;
    if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
      throw new RangeError("timeoutMs must be a positive finite number");
    }
    this.timeoutMs = timeoutMs;
  }

  health(): Promise<ApiEnvelope<unknown>> {
    return this.request("/v1/health");
  }

  providerStatus(): Promise<ApiEnvelope<ProviderStatus>> {
    return this.request("/v1/provider/status");
  }

  catalog(): Promise<ApiEnvelope<unknown>> {
    return this.request("/v1/catalog");
  }

  strategies(): Promise<ApiEnvelope<unknown>> {
    return this.request("/v1/strategies");
  }

  createStrategy(input: CreateStrategyInput): Promise<ApiEnvelope<unknown>> {
    return this.request("/v1/strategies", { method: "POST", body: JSON.stringify(input) });
  }

  signals(): Promise<ApiEnvelope<unknown>> {
    return this.request("/v1/signals");
  }

  signalAdvice(signalId: string): Promise<ApiEnvelope<unknown>> {
    return this.request(`/v1/signals/${encodeURIComponent(signalId)}/advice`, { method: "POST" });
  }

  portfolios(): Promise<ApiEnvelope<unknown>> {
    return this.request("/v1/portfolios");
  }

  registerManualExecution(portfolioId: string, input: ManualExecutionInput): Promise<ApiEnvelope<unknown>> {
    return this.request(`/v1/portfolios/${portfolioId}/executions`, {
      method: "POST",
      body: JSON.stringify(input),
    });
  }

  placePaperOrder(input: PaperOrderInput): Promise<ApiEnvelope<unknown>> {
    return this.request("/v1/paper-orders", { method: "POST", body: JSON.stringify(input) });
  }

  saveSellRule(positionId: string, input: SellRuleInput): Promise<ApiEnvelope<unknown>> {
    return this.request(`/v1/positions/${positionId}/sell-rules`, {
      method: "POST",
      body: JSON.stringify(input),
    });
  }

  rankings(period: RankingPeriod = "3M"): Promise<ApiEnvelope<unknown>> {
    return this.request(`/v1/rankings?period=${encodeURIComponent(period)}`);
  }

  updateVisibility(input: ProfileVisibilityInput): Promise<ApiEnvelope<unknown>> {
    return this.request("/v1/me/visibility", { method: "PUT", body: JSON.stringify(input) });
  }

  updateAlertSettings(input: AlertSettingsInput): Promise<ApiEnvelope<unknown>> {
    return this.request("/v1/alert-settings", { method: "PUT", body: JSON.stringify(input) });
  }

  private async request<T>(path: string, init?: RequestInit): Promise<ApiEnvelope<T>> {
    const headers = new Headers(init?.headers);
    headers.set("content-type", "application/json");
    if (this.accessToken) headers.set("authorization", `Bearer ${this.accessToken}`);

    const controller = new AbortController();
    let timedOut = false;
    let timeout: ReturnType<typeof setTimeout> | undefined;
    const timeoutPromise = new Promise<never>((_resolve, reject) => {
      timeout = setTimeout(() => {
        timedOut = true;
        controller.abort();
        reject(new Error("request timeout"));
      }, this.timeoutMs);
    });

    try {
      const response = await Promise.race([
        this.fetcher(`${this.baseUrl}${path}`, {
          ...init,
          headers,
          signal: controller.signal,
        }),
        timeoutPromise,
      ]);
      const parsed = await Promise.race([readResponseBody(response), timeoutPromise]);

      if (!response.ok) {
        throw new ApiError(
          errorMessage(parsed.body) ?? "API 요청 실패",
          response.status,
          parsed.isJson ? parsed.body : { rawBody: parsed.rawBody },
        );
      }

      if (!parsed.isJson) {
        throw new ApiError("API 응답이 JSON 형식이 아닙니다.", response.status, {
          rawBody: parsed.rawBody,
        });
      }

      const issues = envelopeIssues(parsed.body);
      if (issues.length > 0) {
        throw new ApiError("API 응답 형식이 올바르지 않습니다.", response.status, {
          issues,
          body: parsed.body,
        });
      }

      return parsed.body as ApiEnvelope<T>;
    } catch (error) {
      if (timedOut) {
        throw new ApiError("API 요청 시간이 초과되었습니다.", 408, {
          timeoutMs: this.timeoutMs,
        });
      }
      if (error instanceof ApiError) throw error;
      throw new ApiError("API 연결에 실패했습니다.", 0, error);
    } finally {
      if (timeout !== undefined) clearTimeout(timeout);
    }
  }
}

interface ParsedResponseBody {
  body: unknown;
  isJson: boolean;
  rawBody: string;
}

async function readResponseBody(response: Response): Promise<ParsedResponseBody> {
  const rawBody = await response.text();
  try {
    return { body: JSON.parse(rawBody) as unknown, isJson: true, rawBody };
  } catch {
    return { body: undefined, isJson: false, rawBody };
  }
}

function errorMessage(body: unknown): string | undefined {
  if (!isRecord(body)) return undefined;
  const message = body["message"];
  return typeof message === "string" && message.trim().length > 0 ? message : undefined;
}

function envelopeIssues(body: unknown): string[] {
  if (!isRecord(body)) return ["envelope"];

  const issues: string[] = [];
  if (!Object.prototype.hasOwnProperty.call(body, "data")) issues.push("data");
  const meta = body["meta"];
  if (!isRecord(meta)) return [...issues, "meta"];

  const requestId = meta["requestId"];
  if (typeof requestId !== "string" || requestId.trim().length === 0) {
    issues.push("meta.requestId");
  }
  const generatedAt = meta["generatedAt"];
  if (typeof generatedAt !== "string" || !isValidDateTime(generatedAt)) {
    issues.push("meta.generatedAt");
  }
  if (meta["dataSource"] !== "postgres" && meta["dataSource"] !== "unavailable") {
    issues.push("meta.dataSource");
  }
  return issues;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isValidDateTime(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(value)
    && Number.isFinite(Date.parse(value));
}
