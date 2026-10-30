"""Шов до моделі для доведеної роботи.

Будь-яка відповідь моделі в цьому зрізі купується лише через `AIRouter`
(`ai.provider`), тобто проходить касу `_runtime_note_llm_call(task_id)` —
лічильник і стелю викликів на задачу в `AgentRuntime.note_llm_call`. Своїх
клієнтів провайдерів тут нема і бути не повинно: обхід каси — це
`genai.Client` напряму, як у `agent/actions/vision.py`, і саме такого тут не
роблять.

`generate_no_tools` — навмисно: контракт і зміна пишуться як текст, а не
виконанням інструментів моделі; усі дії над репозиторієм робить runner.
"""
from __future__ import annotations

from typing import Any, Protocol


class Brain(Protocol):
    async def ask(self, *, purpose: str, system: str, prompt: str) -> str: ...


class BrainRefused(RuntimeError):
    """Каса або провайдер відмовили — робота паркується, а не вигадує відповідь."""


class RouterBrain:
    def __init__(self, *, task_id: str | None, router: Any | None = None) -> None:
        self.task_id = task_id
        self._router = router

    def _resolve(self) -> Any:
        if self._router is None:
            from ai.provider import ai_router
            self._router = ai_router
        return self._router

    async def ask(self, *, purpose: str, system: str, prompt: str) -> str:
        router = self._resolve()
        try:
            resp = await router.generate_no_tools(
                prompt, system, [], task_id=self.task_id,
            )
        except Exception as exc:  # каса (call_budget_exhausted), квота, мережа
            raise BrainRefused(f"{purpose}: {exc}") from exc
        text = getattr(resp, "content", None)
        if not isinstance(text, str) or not text.strip():
            raise BrainRefused(f"{purpose}: порожня відповідь")
        return text
