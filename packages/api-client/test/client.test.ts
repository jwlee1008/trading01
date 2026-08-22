import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, SignalApiClient } from "../src/index.js";

afterEach(() => {
  vi.useRealTimers();
});

describe("SignalApiClient", () => {
  it("reads provider status from its typed public endpoint", async () => {
    const fetcher = vi.fn(() => Promise.resolve(new Response(JSON.stringify({
      data: { provider: "mock", state: "CONNECTED", lastCandleAt: null, delayed: false },
      meta: { requestId: "request-1", generatedAt: "2026-08-15T00:00:00.000Z", mock: true },
    }), { status: 200, headers: { "content-type": "application/json" } }))) as unknown as typeof fetch;
    const result = await new SignalApiClient("http://localhost:3000", fetcher).providerStatus();
    expect(result.data.state).toBe("CONNECTED");
    expect(fetcher).toHaveBeenCalledWith("http://localhost:3000/v1/provider/status", expect.any(Object));
  });

  it("requests a structured explanation for one signal", async () => {
    const fetcher = responseFetcher({
      data: { signalId: "sig-1", summary: "설명" },
      meta: { requestId: "request-1", generatedAt: "2026-08-15T00:00:00.000Z", mock: true },
    });
    const result = await new SignalApiClient("http://localhost:3000", fetcher).signalAdvice("sig/1");
    expect(result.data).toMatchObject({ signalId: "sig-1" });
    expect(fetcher).toHaveBeenCalledWith("http://localhost:3000/v1/signals/sig%2F1/advice", expect.objectContaining({ method: "POST" }));
  });

  it("aborts a request at the configured timeout", async () => {
    vi.useFakeTimers();
    const fetcher = vi.fn(() => new Promise<Response>(() => undefined)) as unknown as typeof fetch;
    const client = new SignalApiClient("http://localhost:3000", fetcher, undefined, { timeoutMs: 50 });

    const assertion = expect(client.health()).rejects.toMatchObject({
      name: "ApiError",
      status: 408,
      details: { timeoutMs: 50 },
    });
    await vi.advanceTimersByTimeAsync(50);
    await assertion;
  });

  it("rejects a malformed success envelope", async () => {
    const fetcher = responseFetcher({
      payload: { ok: true },
      meta: { requestId: "", generatedAt: "not-a-date", mock: "yes" },
    });

    await expect(new SignalApiClient("http://localhost:3000", fetcher).health()).rejects.toMatchObject({
      name: "ApiError",
      status: 200,
      details: {
        issues: ["data", "meta.requestId", "meta.generatedAt", "meta.mock"],
      },
    });
  });

  it("rejects a non-JSON success body", async () => {
    const fetcher = vi.fn(() => Promise.resolve(new Response("upstream html", {
      status: 200,
      headers: { "content-type": "text/html" },
    }))) as unknown as typeof fetch;

    await expect(new SignalApiClient("http://localhost:3000", fetcher).health()).rejects.toMatchObject({
      name: "ApiError",
      status: 200,
      details: { rawBody: "upstream html" },
    });
  });

  it("handles a non-JSON error body without masking HTTP status", async () => {
    const fetcher = vi.fn(() => Promise.resolve(new Response("gateway down", {
      status: 503,
      headers: { "content-type": "text/plain" },
    }))) as unknown as typeof fetch;

    const error = await new SignalApiClient("http://localhost:3000", fetcher).health().catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      message: "API 요청 실패",
      status: 503,
      details: { rawBody: "gateway down" },
    });
  });

  it("keeps a JSON API error message", async () => {
    const fetcher = vi.fn(() => Promise.resolve(new Response(JSON.stringify({ message: "인증 실패" }), {
      status: 401,
      headers: { "content-type": "application/json" },
    }))) as unknown as typeof fetch;

    await expect(new SignalApiClient("http://localhost:3000", fetcher).health()).rejects.toMatchObject({
      message: "인증 실패",
      status: 401,
      details: { message: "인증 실패" },
    });
  });

  it("rejects invalid timeout configuration", () => {
    expect(() => new SignalApiClient("http://localhost:3000", fetch, undefined, { timeoutMs: 0 })).toThrow(RangeError);
  });
});

function responseFetcher(body: unknown): typeof fetch {
  return vi.fn(() => Promise.resolve(new Response(JSON.stringify(body), {
    status: 200,
    headers: { "content-type": "application/json" },
  })));
}
