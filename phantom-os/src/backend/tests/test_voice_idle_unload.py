"""Voice models are loaded on demand and returned after idle (RAM diet)."""
import voice.pipeline as vp


def test_unload_after_idle(monkeypatch):
    sentinel = object()
    monkeypatch.setattr(vp, "_vosk_model", sentinel)
    vp._touch()
    t0 = vp._last_use
    assert vp.unload_if_idle(300, now=t0 + 10) is False
    assert vp._vosk_model is sentinel
    assert vp.unload_if_idle(300, now=t0 + 301) is True
    assert vp._vosk_model is None


def test_nothing_loaded_nothing_to_do(monkeypatch):
    monkeypatch.setattr(vp, "_stt", None)
    monkeypatch.setattr(vp, "_tts", None)
    monkeypatch.setattr(vp, "_vosk_model", None)
    import voice.vad as vad
    monkeypatch.setattr(vad, "_vad_session", None)
    assert vp.unload_if_idle(1, now=10**9) is False


def test_voice_is_not_preloaded_at_boot_by_default():
    from config import PhantomConfig
    f = PhantomConfig.model_fields
    assert f["voice_preload_at_startup"].default is False
    assert f["memory_warm_at_startup"].default is False
