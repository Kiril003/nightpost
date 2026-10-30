"""OpenAI-сумісний провайдер — будь-який сервер з `/chat/completions`.

Одним адаптером закриває OpenAI, OpenRouter, Groq, Mistral, DeepSeek,
LM Studio, vLLM, llama.cpp-server: усі вони говорять тим самим протоколом,
різняться лише `ai_openai_base_url`, ключем і назвою моделі. Без SDK —
лише httpx, як і Anthropic-адаптер.

Уміє: текст (повністю і потоком), рідний виклик інструментів (`tools`),
і «око» — `see()` з картинкою як data-URI в `image_url`. Чи справді
модель бачить, вирішує власник: `ai_openai_vision` у налаштуваннях.
"""
from __future__ import annotations

import base64
import json
import logging
from typing import Any, AsyncIterator

import httpx

from config import config
from ai.provider import AIProvider, AIResponse
from ai.tool_use import ToolCallResult, ToolErrorKind, ToolSchema, ToolUseError

logger = logging.getLogger(__name__)

_DEFAULT_TIMEOUT_S = 60.0


def _classify_openai_error(exc: Exception) -> tuple[ToolErrorKind, bool, float | None]:
    """httpx-виняток → (вид, чи варто повторити, retry-after)."""
    if isinstance(exc, httpx.TimeoutException):
        return ToolErrorKind.TIMEOUT, True, None
    if isinstance(exc, httpx.HTTPStatusError):
        code = exc.response.status_code
        retry_after: float | None = None
        ra = exc.response.headers.get("retry-after")
        if ra:
            try:
                retry_after = float(ra)
            except ValueError:
                retry_after = None
        if code == 429:
            text = exc.response.text.lower()
            if "insufficient_quota" in text or "billing" in text:
                return ToolErrorKind.QUOTA_EXHAUSTED, False, retry_after
            return ToolErrorKind.RATE_LIMIT, True, retry_after
        if code in (401, 402, 403):
            return ToolErrorKind.QUOTA_EXHAUSTED, False, retry_after
        if code == 400:
            return ToolErrorKind.INVALID_ARGS, False, retry_after
        if code >= 500:
            return ToolErrorKind.PROVIDER_UNAVAILABLE, True, retry_after
        return ToolErrorKind.NETWORK, True, retry_after
    if isinstance(exc, httpx.TransportError):
        return ToolErrorKind.NETWORK, True, None
    lower = str(exc).lower()
    if "timeout" in lower or "timed out" in lower:
        return ToolErrorKind.TIMEOUT, True, None
    return ToolErrorKind.NETWORK, True, None


def _build_messages(system_prompt: str, user_message: str, history: list[dict]) -> list[dict]:
    out: list[dict] = []
    if system_prompt:
        out.append({"role": "system", "content": system_prompt})
    for m in history or []:
        role = m.get("role")
        content = m.get("content", "")
        if role in ("user", "assistant") and content:
            out.append({"role": role, "content": content})
    out.append({"role": "user", "content": user_message})
    return out


def _tool_name_out(name: str) -> str:
    # Імена функцій в OpenAI-протоколі — ^[a-zA-Z0-9_-]+$; крапку кодуємо.
    return name.replace(".", "__")


def _tool_name_in(name: str) -> str:
    return name.replace("__", ".")


class OpenAICompatProvider(AIProvider):
    """Будь-який сервер OpenAI Chat Completions."""

    name = "openai"

    @property
    def _base(self) -> str:
        return (getattr(config, "ai_openai_base_url", "") or "").rstrip("/")

    @property
    def _api_key(self) -> str:
        return getattr(config, "ai_openai_api_key", "") or ""

    @property
    def _model(self) -> str:
        return getattr(config, "ai_openai_model", "") or ""

    def configured(self) -> bool:
        """Є куди і чим питати. Локальний сервер (LM Studio) ключа не має —
        тож ключ обов'язковий лише для не-локальної адреси."""
        if not self._base or not self._model:
            return False
        local = any(h in self._base for h in ("://127.", "://localhost", "://0.0.0.0", "://[::1]"))
        return local or bool(self._api_key)

    def _headers(self) -> dict[str, str]:
        h = {"content-type": "application/json"}
        if self._api_key:
            h["authorization"] = f"Bearer {self._api_key}"
        return h

    def _require(self) -> None:
        if not self.configured():
            raise RuntimeError("openai-compatible provider not configured (base_url/model/key)")

    async def _post(self, body: dict[str, Any], timeout_s: float = _DEFAULT_TIMEOUT_S) -> dict:
        async with httpx.AsyncClient(timeout=httpx.Timeout(timeout_s)) as client:
            resp = await client.post(f"{self._base}/chat/completions", headers=self._headers(), json=body)
            if resp.status_code >= 400:
                logger.warning("openai-compat %s: %s", resp.status_code, resp.text[:200])
            resp.raise_for_status()
            return resp.json()

    @staticmethod
    def _text_and_tokens(data: dict) -> tuple[str, int]:
        choices = data.get("choices") or []
        msg = (choices[0].get("message") if choices else None) or {}
        content = msg.get("content") or ""
        if isinstance(content, list):  # деякі сервери віддають частини
            content = "".join(p.get("text", "") for p in content if isinstance(p, dict))
        usage = data.get("usage") or {}
        tokens = int(usage.get("total_tokens") or 0) or (
            int(usage.get("prompt_tokens") or 0) + int(usage.get("completion_tokens") or 0)
        )
        return str(content).strip(), tokens

    async def generate(
        self,
        user_message: str,
        system_prompt: str,
        history: list[dict],
        *,
        user_id: str | None = None,
        model_override: str | None = None,
    ) -> AIResponse:
        self._require()
        data = await self._post({
            "model": model_override or self._model,
            "messages": _build_messages(system_prompt, user_message, history),
            "max_tokens": config.ai_max_tokens,
            "temperature": config.ai_temperature,
        })
        text, tokens = self._text_and_tokens(data)
        return AIResponse(content=text, provider="openai", tokens_used=tokens)

    async def generate_stream(
        self,
        user_message: str,
        system_prompt: str,
        history: list[dict],
        *,
        model_override: str | None = None,
    ) -> AsyncIterator[str]:
        self._require()
        body = {
            "model": model_override or self._model,
            "messages": _build_messages(system_prompt, user_message, history),
            "max_tokens": config.ai_max_tokens,
            "temperature": config.ai_temperature,
            "stream": True,
        }
        timeout = httpx.Timeout(_DEFAULT_TIMEOUT_S, read=None)
        async with httpx.AsyncClient(timeout=timeout) as client:
            async with client.stream(
                "POST", f"{self._base}/chat/completions", headers=self._headers(), json=body,
            ) as resp:
                resp.raise_for_status()
                async for line in resp.aiter_lines():
                    if not line.startswith("data: "):
                        continue
                    payload = line[len("data: "):].strip()
                    if payload == "[DONE]":
                        break
                    try:
                        evt = json.loads(payload)
                    except json.JSONDecodeError:
                        continue
                    for choice in evt.get("choices") or []:
                        chunk = (choice.get("delta") or {}).get("content")
                        if chunk:
                            yield chunk

    async def see(
        self,
        image: bytes,
        prompt: str,
        *,
        mime: str = "image/png",
        want_json: bool = False,
        model_override: str | None = None,
    ) -> str:
        """Картинка + питання → текст. JSON-режим — лише як прохання в
        `response_format`; сервер, що його не знає, відповість звичайним текстом."""
        self._require()
        url = f"data:{mime};base64,{base64.b64encode(image).decode('ascii')}"
        body: dict[str, Any] = {
            "model": model_override or self._model,
            "messages": [{
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {"type": "image_url", "image_url": {"url": url}},
                ],
            }],
            "max_tokens": config.ai_max_tokens,
        }
        if want_json:
            body["response_format"] = {"type": "json_object"}
        data = await self._post(body)
        return self._text_and_tokens(data)[0]

    async def health_check(self) -> bool:
        if not self.configured():
            return False
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(10.0)) as client:
                resp = await client.get(f"{self._base}/models", headers=self._headers())
                return resp.status_code == 200
        except Exception:  # pragma: no cover — мережа
            return False

    async def call_with_tools(
        self,
        *,
        system_prompt: str,
        user_message: str,
        tools: list[ToolSchema],
        history: list[dict] | None = None,
        user_id: str | None = None,
        model_override: str | None = None,
    ) -> ToolCallResult | ToolUseError:
        model = model_override or self._model
        if not self.configured():
            return ToolUseError(
                kind=ToolErrorKind.QUOTA_EXHAUSTED,
                message="openai-compatible provider not configured",
                retriable=False, provider="openai", model=model,
            )
        if not tools:
            return ToolUseError(
                kind=ToolErrorKind.INVALID_ARGS, message="no tools provided",
                retriable=False, provider="openai", model=model,
            )
        valid = {t.name for t in tools}
        body = {
            "model": model,
            "messages": _build_messages(system_prompt, user_message, history or []),
            "tools": [
                {
                    "type": "function",
                    "function": {
                        "name": _tool_name_out(t.name),
                        "description": (t.description or "")[:1024],
                        "parameters": t.parameters or {"type": "object", "properties": {}},
                    },
                }
                for t in tools
            ],
            "tool_choice": "required",
            "max_tokens": config.ai_max_tokens,
        }
        try:
            data = await self._post(body)
        except Exception as exc:
            kind, retriable, retry_after = _classify_openai_error(exc)
            return ToolUseError(
                kind=kind, message=f"openai-compat: {exc}", retriable=retriable,
                provider="openai", model=model, retry_after_s=retry_after,
            )
        choices = data.get("choices") or []
        msg = (choices[0].get("message") if choices else None) or {}
        calls = msg.get("tool_calls") or []
        if not calls:
            return ToolUseError(
                kind=ToolErrorKind.MODEL_REFUSED,
                message=f"no tool call; text={str(msg.get('content') or '')[:200]!r}",
                retriable=True, provider="openai", model=model,
            )
        fn = calls[0].get("function") or {}
        name = _tool_name_in(str(fn.get("name") or ""))
        if name not in valid:
            return ToolUseError(
                kind=ToolErrorKind.UNKNOWN_TOOL, message=f"unknown tool {name!r}",
                retriable=True, provider="openai", model=model,
            )
        raw_args = fn.get("arguments") or "{}"
        try:
            args = json.loads(raw_args) if isinstance(raw_args, str) else dict(raw_args)
        except (json.JSONDecodeError, TypeError, ValueError) as exc:
            return ToolUseError(
                kind=ToolErrorKind.PARSE_FAILED, message=f"bad arguments JSON: {exc}",
                retriable=True, provider="openai", model=model,
            )
        if not isinstance(args, dict):
            args = {}
        return ToolCallResult(
            tool_name=name, arguments=args, raw_reasoning=str(msg.get("content") or ""),
            provider="openai", model=model,
        )


__all__ = ["OpenAICompatProvider", "_classify_openai_error"]
