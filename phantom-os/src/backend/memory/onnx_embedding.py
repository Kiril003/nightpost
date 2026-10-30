"""
Вбудовування пам'яті без torch — ONNX Runtime + tokenizers.

Навіщо
──────
Єдине, для чого вузлу потрібен torch, — вектори пам'яті (e5 через
SentenceTransformer). torch із PyPI тягне CUDA: torch + nvidia-* + triton —
5,3 ГБ із 5,7 ГБ вузла в пакеті ПК. ONNX Runtime і tokenizers у вузлі вже є
(детектор мовлення, Chroma), тож той самий трансформер можна рахувати ними.

Рівність зі старим шляхом — умова, а не побажання: сховище Chroma, записане
SentenceTransformer, мусить читатися цим кодом без переіндексації (відбиток
моделі в `embedding_fn.py` звіряє лише ІМ'Я моделі). Тому тут рівно те, що
робить конвеєр sentence-transformers для e5: токенізатор моделі з обрізанням
до 512, трансформер, середнє за маскою уваги, L2-нормалізація. Доведено
тестом `tests/test_onnx_embedding_parity.py` на тій самій моделі в обох рушіях.

Звідки модель
─────────────
1. `PHANTOM_EMBEDDING_DIR` — тека з `model.onnx` (або `onnx/model.onnx`) і
   `tokenizer.json` (пакет, що везе модель із собою, або офлайн-установка).
2. Кеш Hugging Face / завантаження: `onnx/model.onnx` і `tokenizer.json` із
   репозиторію моделі (у `intfloat/multilingual-e5-small` ONNX лежить поруч
   з вагами).
"""
from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import Any, Sequence

logger = logging.getLogger(__name__)

#: Межа e5 (і `max_seq_length` sentence-transformers для неї).
MAX_TOKENS = 512

_ONNX_CANDIDATES = ("model.onnx", "onnx/model.onnx")


def _from_dir(directory: Path) -> tuple[Path, Path] | None:
    tokenizer = directory / "tokenizer.json"
    if not tokenizer.is_file():
        return None
    for rel in _ONNX_CANDIDATES:
        if (directory / rel).is_file():
            return directory / rel, tokenizer
    return None


def resolve_model_files(model_name: str) -> tuple[Path, Path]:
    """`(model.onnx, tokenizer.json)` для моделі — з теки або з кешу/мережі."""
    override = os.environ.get("PHANTOM_EMBEDDING_DIR", "").strip()
    if override:
        found = _from_dir(Path(override))
        if found is None:
            raise FileNotFoundError(
                f"PHANTOM_EMBEDDING_DIR={override}: немає tokenizer.json і model.onnx"
            )
        return found
    local = Path(model_name)
    if local.is_dir():
        found = _from_dir(local)
        if found is not None:
            return found

    from huggingface_hub import hf_hub_download

    def fetch(filename: str) -> Path:
        # Спершу кеш: без мережі hub ретраїть із наростаючими паузами.
        try:
            return Path(hf_hub_download(model_name, filename, local_files_only=True))
        except Exception:
            return Path(hf_hub_download(model_name, filename))

    return fetch("onnx/model.onnx"), fetch("tokenizer.json")


class OnnxMeanPoolEmbedding:
    """Chroma-сумісна функція вбудовування: `__call__(texts) -> list[list[float]]`."""

    def __init__(self, model_path: Path | str, tokenizer_path: Path | str,
                 *, max_tokens: int = MAX_TOKENS, threads: int | None = None) -> None:
        import onnxruntime as ort
        from tokenizers import Tokenizer

        self._tokenizer = Tokenizer.from_file(str(tokenizer_path))
        self._tokenizer.enable_truncation(max_length=max_tokens)
        # XLM-R (e5 multilingual) — `<pad>`, BERT — `[PAD]`.
        pad = next((t for t in ("<pad>", "[PAD]") if self._tokenizer.token_to_id(t) is not None), "[PAD]")
        self._tokenizer.enable_padding(pad_id=self._tokenizer.token_to_id(pad) or 0, pad_token=pad)

        options = ort.SessionOptions()
        if threads:
            options.intra_op_num_threads = threads
        self._session = ort.InferenceSession(
            str(model_path), sess_options=options, providers=["CPUExecutionProvider"]
        )
        self._inputs = {i.name for i in self._session.get_inputs()}
        self._model_path = str(model_path)

    def name(self) -> str:  # Chroma 0.5 зберігає ім'я функції
        return "phantom_onnx_mean_pool"

    def embed(self, texts: Sequence[str]) -> Any:
        import numpy as np

        if not texts:
            return np.zeros((0, 0), dtype=np.float32)
        encodings = self._tokenizer.encode_batch(list(texts))
        ids = np.array([e.ids for e in encodings], dtype=np.int64)
        mask = np.array([e.attention_mask for e in encodings], dtype=np.int64)
        feed = {"input_ids": ids, "attention_mask": mask}
        if "token_type_ids" in self._inputs:
            feed["token_type_ids"] = np.zeros_like(ids)
        feed = {k: v for k, v in feed.items() if k in self._inputs}
        hidden = self._session.run(None, feed)[0]  # (batch, tokens, dim)
        weights = mask[..., None].astype(hidden.dtype)
        pooled = (hidden * weights).sum(axis=1) / np.clip(weights.sum(axis=1), 1e-9, None)
        norms = np.linalg.norm(pooled, axis=1, keepdims=True)
        return (pooled / np.clip(norms, 1e-12, None)).astype(np.float32)

    def __call__(self, input: Any) -> list[list[float]]:  # noqa: A002 — протокол Chroma
        texts = [input] if isinstance(input, str) else [str(t) for t in input]
        return [row.tolist() for row in self.embed(texts)]


def sentence_transformers_available() -> bool:
    """Чи може вузол рахувати вектори старим шляхом (torch + sentence-transformers)."""
    try:
        import sentence_transformers  # noqa: F401
    except Exception:  # ImportError, або CUDA-torch без бібліотек nvidia
        return False
    return True


def build_onnx_embedding(model_name: str) -> OnnxMeanPoolEmbedding:
    model_path, tokenizer_path = resolve_model_files(model_name)
    logger.info("вбудовування пам'яті — ONNX (%s)", model_path)
    return OnnxMeanPoolEmbedding(model_path, tokenizer_path)
