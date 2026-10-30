import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

const renameConversation = vi.fn();

vi.mock("../services/messengerApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../services/messengerApi")>();
  return {
    ...actual,
    messengerApi: {
      ...actual.messengerApi,
      renameConversation: (...a: unknown[]) => renameConversation(...a),
      listContacts: vi.fn().mockResolvedValue([]),
      listConversations: vi.fn().mockResolvedValue([]),
    },
  };
});
vi.mock("../services/phantomSelfApi", () => ({ phantomSelfApi: { facts: vi.fn().mockResolvedValue([]) } }));
vi.mock("../services/peerWhere", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../services/peerWhere")>();
  return {
    ...actual,
    whereApi: { ...actual.whereApi, get: vi.fn().mockRejectedValue(new Error("offline")) },
    onWhereChanged: vi.fn(() => () => {}),
  };
});

import { PeerProfileSheet } from "../components/messenger/PeerProfileSheet";

afterEach(() => {
  cleanup();
  renameConversation.mockReset();
});

const rename = (to: string) => {
  fireEvent.click(screen.getByTestId("info-more"));
  fireEvent.click(screen.getByRole("menuitem", { name: "Змінити" }));
  fireEvent.change(screen.getByLabelText("Як ви називаєте цю людину"), { target: { value: to } });
  fireEvent.click(screen.getByText("Готово"));
};

describe("перейменування людини в профілі", () => {
  it("нове імʼя видно одразу, навіть коли викликач не оновлює стор", async () => {
    renameConversation.mockResolvedValue({});
    render(<PeerProfileSheet inline peerNodeId="node-a" chatId="c1" name="Олена" onClose={() => {}} />);
    rename("Мама");
    await waitFor(() => expect(screen.getByTestId("peer-profile-name")).toHaveTextContent("Мама"));
    expect(renameConversation).toHaveBeenCalledWith("c1", "Мама");
  });

  it("при відмові поле лишається з видимою помилкою, а не мовчки закривається", async () => {
    renameConversation.mockRejectedValue(new Error("x"));
    render(<PeerProfileSheet inline peerNodeId="node-a" chatId="c1" name="Олена" onClose={() => {}} />);
    rename("Мама");
    expect(await screen.findByTestId("peer-profile-rename-note")).toBeInTheDocument();
    expect(screen.getByLabelText("Як ви називаєте цю людину")).toBeInTheDocument();
  });
});
