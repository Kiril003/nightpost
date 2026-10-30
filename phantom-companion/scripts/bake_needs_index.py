#!/usr/bin/env python3
"""Bake core-data/src/main/assets/country/ua/needs_index.bin — the offline
meaning-search vector index for pharmacies + clinics + invincibility points.

Run on a machine with the corpus + model (phantom-gate:~/meaning-index/ as of
06.09.2026), not part of the Android build. Requires: numpy, onnxruntime,
tokenizers. Re-run whenever official_facilities.json / invincibility_points.json
are re-baked — this script reads their `generated` epoch and stamps it into
the index header so a stale index at runtime is detected, not silently wrong.

    python3 bake_needs_index.py \
        --corpus ~/meaning-index/out/corpus_full.jsonl \
        --model ~/meaning-index/models/minilm-l12-multi/onnx/model_qint8_arm64.onnx \
        --tokenizer-json ~/meaning-index/models/minilm-l12-multi/tokenizer.json \
        --official-facilities <repo>/core-data/src/main/assets/country/ua/official_facilities.json \
        --invincibility <repo>/core-data/src/main/assets/country/ua/invincibility_points.json \
        --out <repo>/core-data/src/main/assets/country/ua/needs_index.bin

Binary layout (little-endian) — see OfflineVectorIndex.fromBinary in Kotlin:
  magic "PHNV" | version u16=1 | dim u16 | scale u8=127 | count u32
  | modelName: u8 len + utf8 | generated: i64
  | ids: count x i64 | lat: count x f32 | lon: count x f32
  | vecs: count x dim x i8
"""
import argparse
import json
import struct

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

NEEDS_TYPES = {"pharmacy", "clinic", "invincibility"}
MAX_LEN = 128
SCALE = 127


def mean_pool(last_hidden, attn_mask):
    mask = attn_mask[:, :, None].astype(np.float32)
    summed = (last_hidden * mask).sum(axis=1)
    counts = np.clip(mask.sum(axis=1), 1e-9, None)
    return summed / counts


def l2norm(x):
    n = np.linalg.norm(x, axis=1, keepdims=True)
    return x / np.clip(n, 1e-9, None)


def embed(sess, tok, texts, batch_size=32):
    input_names = {i.name for i in sess.get_inputs()}
    out = []
    for i in range(0, len(texts), batch_size):
        batch = texts[i:i + batch_size]
        encs = tok.encode_batch(batch)
        maxlen = max(len(e.ids) for e in encs)
        ids = np.zeros((len(encs), maxlen), dtype=np.int64)
        attn = np.zeros((len(encs), maxlen), dtype=np.int64)
        types = np.zeros((len(encs), maxlen), dtype=np.int64)
        for j, e in enumerate(encs):
            L = len(e.ids)
            ids[j, :L] = e.ids
            attn[j, :L] = e.attention_mask
        feed = {"input_ids": ids, "attention_mask": attn}
        if "token_type_ids" in input_names:
            feed["token_type_ids"] = types
        pooled = l2norm(mean_pool(sess.run(None, feed)[0], attn))
        out.append(pooled.astype(np.float32))
    return np.concatenate(out, axis=0)


def numeric_id(record_id: str) -> int:
    # "fac-464230586478611818" / "inv-1100186219089253512" -> the OSM/registry
    # numeric id, matching PoiEntity.id / the shelter registry's own id.
    return int(record_id.split("-", 1)[1])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", required=True, help="corpus_full.jsonl (id/type/lat/lon/text per line)")
    ap.add_argument("--model", required=True, help="model_qint8_arm64.onnx (the file ModelManager ships)")
    ap.add_argument("--tokenizer-json", required=True)
    ap.add_argument("--official-facilities", required=True)
    ap.add_argument("--invincibility", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    gen_fac = json.load(open(args.official_facilities, encoding="utf-8"))["generated"]
    gen_inv = json.load(open(args.invincibility, encoding="utf-8"))["generated"]
    if gen_fac != gen_inv:
        raise SystemExit(f"official_facilities generated={gen_fac} != invincibility generated={gen_inv} "
                          "-- rebuild both from the same bake before indexing")

    records = []
    with open(args.corpus, encoding="utf-8") as f:
        for line in f:
            r = json.loads(line)
            if r["type"] in NEEDS_TYPES:
                records.append(r)
    n = len(records)
    print(f"needs subset: {n} records")

    tok = Tokenizer.from_file(args.tokenizer_json)
    tok.enable_truncation(max_length=MAX_LEN)
    tok.enable_padding(length=None)
    so = ort.SessionOptions()
    so.intra_op_num_threads = 4
    sess = ort.InferenceSession(args.model, sess_options=so, providers=["CPUExecutionProvider"])

    vecs = embed(sess, tok, [r["text"] for r in records])
    dim = vecs.shape[1]
    q8 = np.round(vecs * SCALE).clip(-127, 127).astype(np.int8)

    ids = np.array([numeric_id(r["id"]) for r in records], dtype="<i8")
    lat = np.array([r["lat"] for r in records], dtype="<f4")
    lon = np.array([r["lon"] for r in records], dtype="<f4")

    model_name = "paraphrase-multilingual-MiniLM-L12-v2".encode("utf-8")
    with open(args.out, "wb") as f:
        f.write(b"PHNV")
        f.write(struct.pack("<H", 1))
        f.write(struct.pack("<H", dim))
        f.write(struct.pack("<B", SCALE))
        f.write(struct.pack("<I", n))
        f.write(struct.pack("<B", len(model_name)))
        f.write(model_name)
        f.write(struct.pack("<q", gen_fac))
        f.write(ids.tobytes())
        f.write(lat.tobytes())
        f.write(lon.tobytes())
        f.write(q8.tobytes())

    import os
    size = os.path.getsize(args.out)
    print(f"wrote {args.out}: {size} bytes ({size / 1024 / 1024:.2f} MB), generated={gen_fac}")


if __name__ == "__main__":
    main()
