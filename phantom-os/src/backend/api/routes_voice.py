"""
Voice routes — TTS, STT.

Push-to-talk contract (Phase 07):
  POST /api/v1/voice/stt   multipart form, `file` = WAV/OGG/… blob.
       Returns {text, confidence, engine, language}.
  POST /api/v1/voice/tts   JSON {text, voice, speed, emotion_scale}.
       Returns audio/wav binary.
  GET  /api/v1/voice/status   config + active engine names — used by the
       Settings screen to tell operators which provider is running.
  POST /api/v1/voice/stop   no body. The operator's explicit interrupt —
       cancels whichever `SentenceSpeaker` is currently on air for this
       user, mid-sentence, regardless of whether it was started by a
       voice-mode chat reply or the agent's proactive voice.say. See
       `stop_speech` below for why one route covers both.

Wake-word always-on and true streaming STT during speech are deferred;
the front-end drives a tap-to-record loop against these HTTP endpoints.
"""
from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, File, HTTPException, Response, UploadFile, status
from pydantic import BaseModel, Field

from config import config
from security.auth import require_auth
from security.jwt_manager import TokenPayload
from voice.pipeline import (
    get_stt_provider,
    get_tts_provider,
    synthesize_text,
    transcribe_blob,
    voice_for_text,
)
from voice.speaking_floor import speaking_floor
from voice.stt_engine import contains_wake_word

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/voice", tags=["voice"])


MAX_STT_BYTES = 10 * 1024 * 1024  # 10 MB — ~5 min of 16 kHz Opus is well under.
MAX_TTS_CHARS = 4_000


class TTSRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=MAX_TTS_CHARS)
    voice: str = ""
    speed: float = 1.0
    emotion_scale: float = 1.0  # accepted but piper ignores for now


class STTResponse(BaseModel):
    text: str
    confidence: float
    engine: str
    # Day-2 D2-A3 (audit F-25): providers populate this when
    # inference fails so the route can return 503 instead of an empty
    # transcript that looks indistinguishable from silence. Stays None
    # for healthy responses; clients ignore the field on success.
    engine_error: str | None = None
    language: str
    wake_word_matched: bool


class StopResponse(BaseModel):
    # False just means nothing was on air when the request landed — a
    # race the UI hits routinely (operator clicks stop as the last
    # sentence finishes on its own). Not an error either way.
    stopped: bool


class StatusResponse(BaseModel):
    stt_engine: str
    tts_engine: str
    stt_mode: str
    language: str
    tts_enabled: bool
    tts_voice: str
    wake_word_enabled: bool
    wake_words: str


@router.post("/stt", response_model=STTResponse)
async def transcribe_speech(
    file: UploadFile = File(...),
    _token: TokenPayload = Depends(require_auth),  # Day-2 D2-A1 (audit F-08)
) -> STTResponse:
    """Transcribe a single audio clip. Non-streaming — suitable for the
    browser's MediaRecorder tap-to-speak flow."""
    raw = await file.read()
    if not raw:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Empty audio upload",
        )
    if len(raw) > MAX_STT_BYTES:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=f"Audio too large ({len(raw)} bytes, max {MAX_STT_BYTES})",
        )
    try:
        result = await transcribe_blob(raw, config.voice_stt_language)
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(exc),
        ) from exc
    except Exception as exc:
        logger.exception("STT pipeline failed")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"STT pipeline error: {exc}",
        ) from exc
    # Phase 18 E-5 — STT engine usage counter for /metrics.
    try:
        from observability import voice_stt_total
        voice_stt_total.inc(engine=result.engine)
    except Exception:  # noqa: BLE001
        pass
    # Day-2 D2-A3 (audit F-25): if the provider populated engine_error
    # (inference failed), surface 503 so the operator sees
    # "STT temporarily unavailable" instead of treating the empty
    # transcript as silence.
    if result.engine_error:
        logger.warning(
            "STT provider reported engine_error: %s — surfacing 503",
            result.engine_error,
        )
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=f"STT engine error: {result.engine_error}",
        )
    return STTResponse(
        text=result.text,
        confidence=result.confidence,
        engine=result.engine,
        language=result.language,
        wake_word_matched=contains_wake_word(result.text),
    )


@router.post("/tts")
async def synthesize_speech(
    req: TTSRequest,
    _token: TokenPayload = Depends(require_auth),  # Day-2 D2-A1 (audit F-08)
) -> Response:
    """Render text to a WAV blob. Respects voice_tts_enabled (returns 100 ms
    of silence when disabled) and voice_tts_voice (falls back to the
    settings default when `req.voice` is empty)."""
    # Phase 9.2: when caller doesn't specify a voice, auto-select UA or EN
    # based on Cyrillic content ratio. Disabled callers always get the
    # configured `voice_tts_voice`.
    if req.voice.strip():
        voice = req.voice.strip()
    else:
        voice = voice_for_text(req.text)
    speed = req.speed if req.speed > 0 else config.voice_tts_speed
    try:
        result = await synthesize_text(req.text, voice, speed)
    except RuntimeError as exc:
        # Model-file-missing → meaningful 503 instead of generic 500.
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=str(exc),
        ) from exc
    except Exception as exc:
        logger.exception("TTS pipeline failed")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"TTS pipeline error: {exc}",
        ) from exc
    # Phase 18 E-5 — TTS counter for /metrics.
    try:
        from observability import voice_tts_total
        voice_tts_total.inc()
    except Exception:  # noqa: BLE001
        pass
    return Response(
        content=result.audio_wav,
        media_type="audio/wav",
        headers={
            "X-Engine": result.engine,
            "X-Voice": result.voice,
            "X-Sample-Rate": str(result.sample_rate),
        },
    )


@router.get("/status", response_model=StatusResponse)
async def voice_status(
    _token: TokenPayload = Depends(require_auth),  # Day-2 D2-A1 (audit F-08)
) -> StatusResponse:
    """Live snapshot of the voice subsystem — surfaces which provider
    actually loaded so a [soon]-vs-wired mismatch is obvious.
    """
    stt = get_stt_provider()
    tts = get_tts_provider()

    return StatusResponse(
        stt_engine=stt.name,
        tts_engine=tts.name,
        stt_mode=config.voice_stt_mode,
        language=config.voice_stt_language,
        tts_enabled=config.voice_tts_enabled,
        tts_voice=config.voice_tts_voice,
        wake_word_enabled=config.voice_wake_word_enabled,
        wake_words=config.voice_wake_words,
    )


@router.post("/stop", response_model=StopResponse)
async def stop_speech(
    _token: TokenPayload = Depends(require_auth),  # Day-2 D2-A1 (audit F-08)
) -> StopResponse:
    """The operator's explicit "stop talking" — the only way to interrupt
    PHANTOM's speech that doesn't require saying or typing something
    else. Design-critic finding: `incremental_tts.stop_for_user()` used
    to be reachable from exactly one place — as a side effect of the
    user sending a new message — and only one of the two paths that
    start a `SentenceSpeaker` (agent/actions/voice_say.py's proactive
    announcements) had ever been given a cancel contract at all. The
    other — every voice-mode conversational reply, `routes_chat.py:768`
    — had none, and it is the far more common source of PHANTOM's
    speech.

    This route does not choose between them. It goes through
    `voice/speaking_floor.py`, the one registry both call sites join
    the moment `SentenceSpeaker.start()` runs — chat replies and
    voice.say alike — so cancelling here stops whichever of the two is
    actually talking, without needing to know which one it was.

    Cancels the in-flight synthesis task and broadcasts `chat`/
    `tts.stop` so the browser drops its queue and pauses whatever WAV
    clip is currently playing — mid-sentence, not at the end of the
    utterance. Honesty note: a sentence whose audio had already left
    over the WebSocket in the same instant can still land and play
    once more; this route cancels the *source*, it cannot reach into
    an already-open audio buffer on the client. That race pre-dates
    this endpoint — it's the same one "a new message interrupts
    playback" has always had — not something introduced here.
    """
    user_id = _token.user_id
    was_speaking = speaking_floor.is_speaking(user_id)
    await speaking_floor.stop(user_id, "operator_stop")
    return StopResponse(stopped=was_speaking)
