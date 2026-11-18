"""Private, bounded CPU speech inference. Business state stays in Sales/CRM."""
import hmac
import json
import os
import resource
import subprocess
import tempfile
import threading
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

import numpy as np
from fastapi import FastAPI, File, HTTPException, Request, UploadFile, Form
from fastapi.responses import JSONResponse
from funasr_onnx import Paraformer, CT_Transformer
from speakers import SpeakerDiarizer, validate_profiles, MODEL as SPEAKER_MODEL
from alignment import speech_windows, timed_words, phrases, join_words, punctuate

MAX_BYTES = 26_214_400
SAMPLE_RATE = 16_000
MAX_SECONDS = 601
MODEL_NAME = "paraformer-large-zh-timestamp-int8"
slot = threading.Lock()


@asynccontextmanager
async def lifespan(app: FastAPI):
    token = os.environ.get("RIGOUR_ASR_TOKEN", "")
    if len(token) < 32:
        raise RuntimeError("RIGOUR_ASR_TOKEN must be configured")
    directory = Path(os.environ["RIGOUR_ASR_MODELS"])
    app.state.token = token
    app.state.asr = Paraformer(str(directory / "asr"), quantize=True,
                              batch_size=1, intra_op_num_threads=2)
    app.state.speakers = SpeakerDiarizer(directory / "speaker")
    app.state.punctuation = CT_Transformer(str(directory / "punctuation"), quantize=True,
                                          intra_op_num_threads=1)
    yield


app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


@app.middleware("http")
async def authorize(request: Request, call_next):
    if request.url.path != "/health":
        supplied = request.headers.get("authorization", "")
        if not hmac.compare_digest(supplied, "Bearer " + request.app.state.token):
            return JSONResponse({"detail": "UNAUTHORIZED"}, status_code=401)
        size = request.headers.get("content-length", "")
        if not size.isdigit() or int(size) > MAX_BYTES + 365_536:
            return JSONResponse({"detail": "AUDIO_SIZE_LIMIT"}, status_code=413)
    return await call_next(request)


@app.get("/health")
def health():
    return {"status": "UP", "model": MODEL_NAME, "speakerModel": SPEAKER_MODEL}


def decode_audio(payload: bytes, maximum=600_999):
    with tempfile.TemporaryDirectory(prefix="rigour-asr-") as directory:
        audio = Path(directory) / "audio"
        audio.write_bytes(payload)
        try:
            decoded = subprocess.run([
                "ffmpeg", "-nostdin", "-v", "error", "-threads", "1", "-i", str(audio),
                "-t", str(maximum / 1000 + 0.001), "-ac", "1", "-ar", str(SAMPLE_RATE),
                "-f", "f32le", "pipe:1"], capture_output=True, timeout=30, check=True)
        except (subprocess.SubprocessError, OSError):
            raise HTTPException(422, "AUDIO_DECODE_FAILED")
    waveform = np.frombuffer(decoded.stdout, dtype="<f4")
    duration_ms = round(len(waveform) * 1000 / SAMPLE_RATE)
    if not 0 < duration_ms <= maximum:
        raise HTTPException(422, "AUDIO_DURATION_LIMIT")
    return waveform


def transcribe_bytes(payload: bytes, profiles):
    started = time.perf_counter()
    waveform = decode_audio(payload)
    duration_ms = round(len(waveform) / 16)
    spans, updated_profiles = app.state.speakers.segments(waveform, profiles)
    words = []
    for begin, finish, left, right in speech_windows(spans, duration_ms):
        chunk = waveform[left*16:right*16]
        if len(chunk) < 400:
            continue
        for start, end, word in timed_words(app.state.asr(chunk), left, begin, finish, duration_ms):
            if words:
                # Adjacent contextual windows can disagree slightly on word timing.
                if start < words[-1][1] and word == words[-1][2]:
                    continue
                start = max(start, words[-1][1])
            if end > start:
                words.append((start, end, word))
    segments = []
    for phrase in phrases(punctuate(words, app.state.punctuation)):
        start, end = phrase[0][0], phrase[-1][1]
        interior = next((identity for left, right, identity in spans
                         if left <= start-300 and right >= end+300 and right-left >= 2500), None)
        ambiguous_ms = sum(max(0, min(end, right)-max(start, left))
                           for left, right, identity in spans if identity is None)
        speaker = (None if ambiguous_ms >= 200 else
                   app.state.speakers.identify(waveform[start*16:end*16], updated_profiles, interior))
        text = join_words([w[2] for w in phrase])
        if (segments and speaker is not None and segments[-1]["speakerId"] == speaker
                and start-segments[-1]["endMs"] < 500 and end-segments[-1]["startMs"] <= 15000):
            segments[-1]["endMs"] = end
            segments[-1]["text"] = join_words([segments[-1]["text"], text])
        else:
            segments.append({"startMs": start, "endMs": end, "text": text, "speakerId": speaker})
    return {"requestId": str(uuid.uuid4()), "model": MODEL_NAME,
            "transcript": "\n".join(item["text"] for item in segments),
            "segments": segments, "durationMs": duration_ms,
            "speakerModel": SPEAKER_MODEL, "speakerProfiles": updated_profiles,
            "processingMs": round((time.perf_counter() - started) * 1000),
            "peakRssMb": round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024, 1)}


@app.post("/v1/transcriptions")
def transcribe(file: UploadFile = File(...), speaker_context: str = Form(...)):
    try:
        if len(speaker_context) > 300_000:
            raise ValueError("SPEAKER_CONTEXT_INVALID")
        profiles = validate_profiles(json.loads(speaker_context))
    except (ValueError, TypeError, AttributeError):
        raise HTTPException(422, "SPEAKER_CONTEXT_INVALID")
    if not slot.acquire(blocking=False):
        raise HTTPException(429, "ASR_BUSY")
    try:
        payload = file.file.read(MAX_BYTES + 1)
        if not payload or len(payload) > MAX_BYTES:
            raise HTTPException(413, "AUDIO_SIZE_LIMIT")
        return transcribe_bytes(payload, profiles)
    finally:
        file.file.close()
        slot.release()


def enrollment(waveform):
    duration = round(len(waveform) / 16)
    if not 15_000 <= duration <= 60_000:
        raise HTTPException(422, "VOICE_QUALITY")
    spans, profiles = app.state.speakers.segments(waveform, [])
    speech = sum(end-start for start, end, identity in spans if identity == 0)
    total = sum(end-start for start, end, _ in spans)
    if len(profiles) != 1 or speech < 8000 or speech < total * 0.9:
        raise HTTPException(422, "VOICE_QUALITY")
    return {"model": SPEAKER_MODEL, "embedding": profiles[0]["embedding"], "speechMs": speech}


@app.post("/v1/voice-profile")
def voice_profile(file: UploadFile = File(...)):
    if not slot.acquire(blocking=False):
        raise HTTPException(429, "ASR_BUSY")
    try:
        payload = file.file.read(2_097_153)
        if not payload or len(payload) > 2_097_152:
            raise HTTPException(413, "AUDIO_SIZE_LIMIT")
        return enrollment(decode_audio(payload, 60_000))
    finally:
        file.file.close()
        slot.release()
