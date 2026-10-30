import { describe, expect, it } from "vitest";
import { avatarLetters } from "../components/messenger/Avatar";

describe("літера аватара", () => {
  it("одна літера, а не дві — «МВ» при шрифті 1.3 ширші за квадрат", () => {
    expect(avatarLetters("Марта Володимирівна")).toBe("М");
  });
  it("розділовий знак не стає літерою («Н·» з «Ноутбук · 9b10»)", () => {
    expect(avatarLetters("Ноутбук · 9b10·41af")).toBe("Н");
    expect(avatarLetters("· bd6e")).toBe("B");
  });
  it("порожнє імʼя — знак питання", () => {
    expect(avatarLetters("")).toBe("?");
    expect(avatarLetters("  ·· ")).toBe("?");
  });
  it("ґ і є у верхньому регістрі", () => {
    expect(avatarLetters("ґанок")).toBe("Ґ");
  });
});

describe("аватар без імені — силует, а не літера", () => {
  it("«Без імені» і порожнє ім'я не дають «Б» чи «?»", async () => {
    const { render } = await import("@testing-library/react");
    const React = await import("react");
    const { Avatar } = await import("../components/messenger/Avatar");
    for (const name of ["Без імені", "", "  "]) {
      const { container, unmount } = render(React.createElement(Avatar, { name }));
      const box = container.firstElementChild as HTMLElement;
      expect(box.hasAttribute("data-nameless")).toBe(true);
      expect(box.textContent).toBe("");
      expect(box.querySelector("svg")).toBeTruthy();
      unmount();
    }
    const { container } = render(React.createElement(Avatar, { name: "Богдан" }));
    expect(container.textContent).toBe("Б");
  });
});
