import { describe, expect, it } from "vitest";
import { FreeMvpEntitlementService } from "../src/index.js";

describe("FreeMvpEntitlementService", () => {
  it("grants every MVP feature through one service", async () => {
    const service = new FreeMvpEntitlementService();
    await expect(service.can("demo-user", "paper.trade")).resolves.toEqual({
      allowed: true,
      source: "MVP_FREE",
    });
  });
});
