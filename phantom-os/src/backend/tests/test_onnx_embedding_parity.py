"""
Вбудовування пам'яті без torch (memory/onnx_embedding.py) дає ТІ САМІ вектори,
що й старий шлях sentence-transformers — інакше сховище, записане одним, мовчки
перетвориться на шум для іншого (див. memory/embedding_fn.py).

Доводимо на крихітній випадковій BERT-моделі, експортованій в ONNX: обидва
рушії, одна модель, тексти різної довжини (вирівнювання й маска мають
значення). Потрібні torch і onnx лише для побудови моделі — у вузлі без torch
тест пропускається, а маршрутизацію перевіряє другий тест.
"""
from __future__ import annotations

import numpy as np
import pytest

TEXTS = ["query: привіт", "query: abc абв де", "query: я", "query: " + "аб " * 40]


@pytest.fixture(scope="module")
def tiny_model(tmp_path_factory):
    torch = pytest.importorskip("torch")
    pytest.importorskip("onnx")
    transformers = pytest.importorskip("transformers")
    d = tmp_path_factory.mktemp("tinybert")
    vocab = ["[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]"] + list(
        "абвгдеєжзиіїйклмнопрстуфхцчшщьюяabcdefghijklmnopqrstuvwxyz:"
    ) + ["при", "віт", "query"]
    (d / "vocab.txt").write_text("\n".join(vocab), encoding="utf-8")
    tok = transformers.BertTokenizerFast(str(d / "vocab.txt"))
    tok.save_pretrained(d)
    cfg = transformers.BertConfig(
        vocab_size=len(vocab), hidden_size=32, num_hidden_layers=2,
        num_attention_heads=2, intermediate_size=64, max_position_embeddings=128,
    )
    torch.manual_seed(0)
    model = transformers.BertModel(cfg).eval()
    model.save_pretrained(d)
    enc = tok(["привіт", "abc"], padding=True, return_tensors="pt")
    names = ["input_ids", "attention_mask", "token_type_ids"]
    torch.onnx.export(
        model, tuple(enc[n] for n in names), str(d / "model.onnx"),
        input_names=names, output_names=["last_hidden_state"],
        dynamic_axes={k: {0: "b", 1: "s"} for k in names + ["last_hidden_state"]},
        opset_version=14, dynamo=False,
    )
    return d


def test_onnx_vectors_equal_sentence_transformers(tiny_model):
    st = pytest.importorskip("sentence_transformers")
    from memory.onnx_embedding import OnnxMeanPoolEmbedding

    word = st.models.Transformer(str(tiny_model), max_seq_length=128)
    pool = st.models.Pooling(word.get_word_embedding_dimension(), pooling_mode="mean")
    reference = st.SentenceTransformer(modules=[word, pool, st.models.Normalize()], device="cpu")
    expected = reference.encode(TEXTS, convert_to_numpy=True)

    onnx = OnnxMeanPoolEmbedding(tiny_model / "model.onnx", tiny_model / "tokenizer.json", max_tokens=128)
    got = np.array(onnx(TEXTS))

    assert got.shape == expected.shape
    np.testing.assert_allclose(got, expected, atol=1e-5)
    # і як Chroma кличе запит — одним рядком
    np.testing.assert_allclose(np.array(onnx(TEXTS[0])[0]), expected[0], atol=1e-5)


def test_without_sentence_transformers_memory_uses_onnx(monkeypatch, tmp_path):
    """Вузол без torch не лишається без пам'яті: фабрика бере ONNX, e5 — з префіксом."""
    import memory.embedding_fn as ef
    import memory.onnx_embedding as oe

    built = []

    class Fake:
        def __call__(self, input):  # noqa: A002
            return [[float(len(t))] for t in input]

    monkeypatch.setattr(oe, "sentence_transformers_available", lambda: False)
    monkeypatch.setattr(oe, "build_onnx_embedding", lambda name: built.append(name) or Fake())

    fn = ef.build_embedding_function("intfloat/multilingual-e5-small")
    assert built == ["intfloat/multilingual-e5-small"]
    assert isinstance(fn, ef.PrefixedEmbeddingFunction)
    assert fn(["ab"]) == [[float(len("query: ab"))]]


def test_embedding_dir_override(tmp_path, monkeypatch):
    from memory.onnx_embedding import resolve_model_files

    (tmp_path / "onnx").mkdir()
    (tmp_path / "onnx" / "model.onnx").write_bytes(b"x")
    (tmp_path / "tokenizer.json").write_text("{}")
    monkeypatch.setenv("PHANTOM_EMBEDDING_DIR", str(tmp_path))
    assert resolve_model_files("intfloat/multilingual-e5-small") == (
        tmp_path / "onnx" / "model.onnx", tmp_path / "tokenizer.json")
    monkeypatch.setenv("PHANTOM_EMBEDDING_DIR", str(tmp_path / "нема"))
    with pytest.raises(FileNotFoundError):
        resolve_model_files("intfloat/multilingual-e5-small")


def test_default_backend_never_touches_torch(monkeypatch):
    """Even with sentence-transformers installed, the default path is ONNX and
    must not so much as probe torch (the probe alone imports it: ~GBs of RAM)."""
    import memory.embedding_fn as ef
    import memory.onnx_embedding as oe

    class Fake:
        def __call__(self, input):  # noqa: A002
            return [[1.0] for _ in input]

    def _probe():
        raise AssertionError("default embedding path probed sentence_transformers/torch")

    monkeypatch.setattr(oe, "sentence_transformers_available", _probe)
    monkeypatch.setattr(oe, "build_onnx_embedding", lambda name: Fake())
    fn = ef.build_embedding_function("intfloat/multilingual-e5-small")
    assert fn(["x"]) == [[1.0]]
