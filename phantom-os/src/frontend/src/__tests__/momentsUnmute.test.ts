import { beforeEach, describe, expect, it, vi } from "vitest";

const request = vi.fn();
vi.mock("../services/api", () => ({ request: (...a: unknown[]) => request(...a) }));

import { useMoments } from "../services/moments";

const row = (id: string, from: string) => ({
  id, from_id: from, from_name: "", mine: false, at_ms: Date.now() - 1000, until_ms: Date.now() + 86_400_000,
  keep: false, audience: { kind: "contacts" }, text: "x", seen: false, to_count: 0, media: [],
});

describe("повернути історії людини", () => {
  beforeEach(() => request.mockReset());

  it("після ввімкнення історії людини знову зʼявляються у списку", async () => {
    request.mockImplementation(async (method: string, path: string) => {
      if (method === "GET" && path === "/messenger/moments") return [row("a", "olena")];
      return {};
    });
    useMoments.setState({ list: [], prefs: { audience: { kind: "contacts" }, keep: false, everyone: false, muted: ["olena"] } });
    await useMoments.getState().mute("olena", false);
    expect(useMoments.getState().list?.map((m) => m.id)).toEqual(["a"]);
    expect(useMoments.getState().prefs?.muted).toEqual([]);
  });
});
