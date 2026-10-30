"""Model ids: current, verified, with a ladder for retirements."""
import pytest

_RETIRED = {"claude-3-7-sonnet-20250219", "gemini-2.0-flash", "gemini-1.5-flash",
            "gemini-flash-lite-latest"}


def test_defaults_avoid_retired_ids():
    from config import PhantomConfig
    f = PhantomConfig.model_fields
    for key in ("ai_gemini_model", "ai_conversational_model", "ai_reasoning_model",
                "ai_background_model", "ai_anthropic_model", "ai_planner_model"):
        assert f[key].default not in _RETIRED, key
    assert f["ai_gemini_model_ladder"].default[0] == f["ai_gemini_model"].default


def test_opus5_payload_has_no_sampling_params():
    from ai.anthropic_provider import _sampling
    assert _sampling("claude-opus-5") == {}
    assert "temperature" in _sampling("claude-sonnet-4-6")


@pytest.mark.asyncio
async def test_gemini_ladder_skips_a_retired_model():
    import ai.gemini_provider as gp
    gp._missing_models.clear()
    seen = []

    class NotFound(Exception):
        code = 404

    class Models:
        async def generate_content(self, *, model, **kw):
            seen.append(model)
            if model == "gone-model":
                raise NotFound("models/gone-model is not found")
            return "ok"

    class Client:
        class aio:
            models = Models()

    out = await gp.generate_with_ladder(Client(), model="gone-model", contents="x")
    assert out == "ok" and seen[0] == "gone-model" and len(seen) == 2
    # Next call skips the dead id straight away.
    seen.clear()
    await gp.generate_with_ladder(Client(), model="gone-model", contents="x")
    assert "gone-model" not in seen
    gp._missing_models.clear()


@pytest.mark.asyncio
async def test_gemini_ladder_does_not_hide_quota_errors():
    import ai.gemini_provider as gp

    class Quota(Exception):
        code = 429

    class Models:
        async def generate_content(self, *, model, **kw):
            raise Quota("RESOURCE_EXHAUSTED")

    class Client:
        class aio:
            models = Models()

    with pytest.raises(Quota):
        await gp.generate_with_ladder(Client(), model="gemini-3.8-flash", contents="x")
