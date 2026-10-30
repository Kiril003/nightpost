"""Payment provider webhook adapters. LemonSqueezy is the launch provider
(merchant-of-record: handles cards/VAT/KYC worldwide, pays out to UA sellers);
the PaymentEvent shape keeps Stripe drop-in ready."""
from __future__ import annotations

import hashlib
import hmac
import json
from dataclasses import dataclass


@dataclass
class PaymentEvent:
    provider: str
    kind: str            # "order_paid" | "refunded" | "ignored"
    order_ref: str
    email: str
    tier: str
    license_key: str | None  # provider-issued key, if the provider generates one


TIER_BY_VARIANT: dict[str, str] = {}  # filled from env at app startup
#: Рівень для замовлення, чий варіант ми не впізнали. Перекривається з env —
#: тут не має лишатись словника чужого продукту.
DEFAULT_TIER = "personal"


def verify_lemonsqueezy_signature(raw_body: bytes, signature: str, secret: str) -> bool:
    expected = hmac.new(secret.encode(), raw_body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, signature or "")


def parse_lemonsqueezy(raw_body: bytes) -> PaymentEvent:
    payload = json.loads(raw_body)
    event = payload.get("meta", {}).get("event_name", "")
    attrs = payload.get("data", {}).get("attributes", {})

    if event == "order_created" and attrs.get("status") == "paid":
        variant = str(attrs.get("first_order_item", {}).get("variant_id", ""))
        return PaymentEvent(
            provider="lemonsqueezy",
            kind="order_paid",
            order_ref=str(payload["data"]["id"]),
            email=attrs.get("user_email", ""),
            tier=TIER_BY_VARIANT.get(variant, DEFAULT_TIER),
            license_key=None,
        )
    if event == "license_key_created":
        return PaymentEvent(
            provider="lemonsqueezy",
            kind="order_paid",
            order_ref=str(attrs.get("order_id", payload["data"]["id"])),
            email=attrs.get("user_email", ""),
            tier=DEFAULT_TIER,
            license_key=attrs.get("key"),
        )
    if event == "order_refunded":
        return PaymentEvent(
            provider="lemonsqueezy",
            kind="refunded",
            order_ref=str(payload["data"]["id"]),
            email=attrs.get("user_email", ""),
            tier="",
            license_key=None,
        )
    return PaymentEvent(
        provider="lemonsqueezy", kind="ignored", order_ref="", email="", tier="", license_key=None
    )
