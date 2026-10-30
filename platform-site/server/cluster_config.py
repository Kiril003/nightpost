from __future__ import annotations

import logging
import os

from solana_pay import MINTS, PUBLIC_RPC
from solana_rpc import UPSTREAM_ENV

log = logging.getLogger("cluster_config")

CLUSTERS = ("devnet", "mainnet-beta")
_KEY = {"devnet": "devnet", "mainnet-beta": "mainnet"}
_HELIUS = {"devnet": "https://devnet.helius-rpc.com/?api-key=", "mainnet-beta": "https://mainnet.helius-rpc.com/?api-key="}
_warned: set[str] = set()


def _env(name: str) -> str:
    return os.environ.get(name, "").strip()


def cluster() -> str:
    asked = _env("SOLANA_CLUSTER") or "devnet"
    if asked not in CLUSTERS:
        if asked not in _warned:
            _warned.add(asked)
            log.warning("unknown SOLANA_CLUSTER, serving devnet")
        return "devnet"
    if asked == "mainnet-beta" and _env("NIGHTPOST_MAINNET_OWNER_YES") != "1":
        if asked not in _warned:
            _warned.add(asked)
            log.warning("SOLANA_CLUSTER=mainnet-beta without NIGHTPOST_MAINNET_OWNER_YES=1, serving devnet")
        return "devnet"
    return asked


def program_id(active: str | None = None) -> str | None:
    active = active or cluster()
    suffix = "MAINNET" if active == "mainnet-beta" else "DEVNET"
    return _env(f"NIGHTPOST_BILL_PROGRAM_ID_{suffix}") or None


def rpc_url(active: str | None = None) -> str | None:
    active = active or cluster()
    key = _env("HELIUS_API_KEY")
    if key:
        return _HELIUS[active] + key
    upstream = _env(UPSTREAM_ENV[_KEY[active]])
    if upstream:
        return upstream
    return PUBLIC_RPC["devnet"] if active == "devnet" else None


def public() -> dict:
    active = cluster()
    mint, decimals = MINTS[_KEY[active]]["usdc"]
    escrow = program_id(active) is not None and rpc_url(active) is not None
    return {
        "cluster": active,
        "programId": program_id(active) if escrow else None,
        "usdcMint": mint,
        "usdcDecimals": decimals,
        "escrow": escrow,
    }
