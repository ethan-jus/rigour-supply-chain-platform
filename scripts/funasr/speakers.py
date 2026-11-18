"""Anonymous speaker labels scoped to one visit; state is owned by Sales, never this service."""
from pathlib import Path
import numpy as np
import sherpa_onnx
from scipy.cluster.hierarchy import linkage, fcluster
from scipy.spatial.distance import pdist

MODEL = "pyannote3-campplus-zh-v1"
DIM = 192
MAX_SPEAKERS = 64
MIN_FRAGMENT_SIMILARITY = 0.65
MIN_IDENTITY_SPEECH_MS = 4000
MIN_IDENTITY_TURN_MS = 2500


def confident_match(vector, profiles, threshold=0.65):
    scores = sorted([(float(np.dot(vector, p["embedding"])), p["id"]) for p in profiles], reverse=True)
    if scores and scores[0][0] >= threshold and (len(scores) == 1 or scores[0][0] - scores[1][0] >= 0.10):
        return scores[0][1]
    return None


def validate_profiles(profiles):
    if not isinstance(profiles, list) or len(profiles) > MAX_SPEAKERS:
        raise ValueError("SPEAKER_CONTEXT_INVALID")
    for index, profile in enumerate(profiles):
        vector = np.asarray(profile.get("embedding", []), dtype=np.float32)
        if (profile.get("id") != index or vector.shape != (DIM,) or not np.isfinite(vector).all()
                or abs(float(np.linalg.norm(vector)) - 1) > 0.02):
            raise ValueError("SPEAKER_CONTEXT_INVALID")
    return profiles


def normalized(vector):
    vector = np.asarray(vector, dtype=np.float32)
    return vector / max(float(np.linalg.norm(vector)), 1e-12)


def exclusive_turns(turns, duration):
    """Do not assign simultaneous voices to one person or transcribe overlapping audio twice."""
    bounds = sorted({max(0, min(duration, round(x * 1000))) for t in turns for x in (t.start, t.end)})
    result = []
    for start, end in zip(bounds, bounds[1:]):
        midpoint = (start + end) / 2000
        active = {t.speaker for t in turns if t.start <= midpoint < t.end}
        if not active:
            continue
        speaker = next(iter(active)) if len(active) == 1 else None
        if result and result[-1][1] == start and result[-1][2] == speaker:
            result[-1] = (result[-1][0], end, speaker)
        else:
            result.append((start, end, speaker))
    return result


def assign_speakers(vectors, profiles, threshold=0.45):
    """Match existing visit voices before allocating new IDs, in first-occurrence order."""
    validate_profiles(profiles)
    updated = [{"id": p["id"], "embedding": list(p["embedding"])} for p in profiles]
    mapping = {}
    used = set()
    # Global best matches avoid an early weak match stealing a later strong match.
    candidates = sorted([(float(np.dot(vector, p["embedding"])), local, p["id"])
                         for local, vector in vectors.items() for p in profiles], reverse=True)
    for score, local, identity in candidates:
        if score >= threshold and local not in mapping and identity not in used:
            mapping[local] = identity
            used.add(identity)
    for local, vector in vectors.items():
        if local in mapping:
            identity = mapping[local]
            # Keep the original reference stable; blend only a strong repeat observation.
            if float(np.dot(vector, updated[identity]["embedding"])) >= 0.65:
                updated[identity]["embedding"] = normalized(np.asarray(updated[identity]["embedding"]) * 0.8 + vector * 0.2).tolist()
        elif len(updated) < MAX_SPEAKERS:
            mapping[local] = len(updated)
            updated.append({"id": len(updated), "embedding": vector.tolist()})
    return mapping, updated


class SpeakerDiarizer:
    def __init__(self, directory: Path):
        embedding = sherpa_onnx.SpeakerEmbeddingExtractorConfig(
            model=str(directory / "campplus.onnx"), num_threads=1)
        config = sherpa_onnx.OfflineSpeakerDiarizationConfig(
            segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
                pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
                    model=str(directory / "segmentation.onnx")), num_threads=1),
            embedding=embedding,
            clustering=sherpa_onnx.FastClusteringConfig(num_clusters=-1, threshold=0.8),
            min_duration_on=0.3, min_duration_off=0.5)
        if not config.validate():
            raise RuntimeError("SPEAKER_MODEL_INVALID")
        self.diarizer = sherpa_onnx.OfflineSpeakerDiarization(config)
        self.extractor = sherpa_onnx.SpeakerEmbeddingExtractor(embedding)

    def embedding(self, waveform):
        stream = self.extractor.create_stream()
        stream.accept_waveform(sample_rate=16000, waveform=waveform)
        stream.input_finished()
        return normalized(self.extractor.compute(stream)) if self.extractor.is_ready(stream) else None

    def identify(self, waveform, profiles, interior_speaker=None):
        # A phrase can correct a coarse diarization boundary, but never creates or
        # updates a voice identity. Short/ambiguous speech remains anonymous.
        if len(waveform) < 16000:
            return interior_speaker
        vector = self.embedding(waveform)
        if vector is None:
            return None
        matched = confident_match(vector, profiles)
        if matched is not None:
            return matched
        # Away from a turn boundary, two independent observations can agree on
        # a noisy phrase. A tie or conflicting acoustic match still stays unknown.
        if interior_speaker is not None and confident_match(vector, profiles, 0.45) == interior_speaker:
            return interior_speaker
        return None

    def segments(self, waveform, profiles):
        turns = exclusive_turns(self.diarizer.process(waveform).sort_by_start_time(), round(len(waveform)/16))
        vectors = {}
        for local in dict.fromkeys(t[2] for t in turns if t[2] is not None):
            # Very short responses and overlapping voices cannot establish an identity reliably.
            samples = sorted([t for t in turns if t[2] == local and t[1]-t[0] >= 1500],
                             key=lambda t: t[1]-t[0], reverse=True)[:3]
            embeddings = []
            for start, end, _ in samples:
                vector = self.embedding(waveform[start*16:min(end,start+15000)*16])
                if vector is not None:
                    embeddings.append(vector)
            if embeddings:
                vectors[local] = normalized(np.mean(embeddings, axis=0))
        # Pool only strongly matching fragments. Average linkage with distance 0.6
        # merged distinct local voices and contaminated the later name match.
        # Complete linkage requires every pair in a pooled identity to agree.
        keys = list(vectors)
        groups = fcluster(linkage(pdist(np.asarray(list(vectors.values())), "cosine"), method="complete"),
                          1 - MIN_FRAGMENT_SIMILARITY, criterion="distance").tolist() if len(keys) > 1 else [1] * len(keys)
        aliases = dict(zip(keys, groups))
        pooled = {group: normalized(np.mean([vectors[k] for k in keys if aliases[k] == group], axis=0))
                  for group in dict.fromkeys(groups)}
        stable = {}
        for group, vector in pooled.items():
            durations = [end-start for start, end, local in turns if aliases.get(local) == group]
            if sum(durations) >= MIN_IDENTITY_SPEECH_MS and max(durations) >= MIN_IDENTITY_TURN_MS:
                stable[group] = vector
        mapping, updated = assign_speakers(stable, profiles)
        # A brief fragment may reuse a strong identity, but cannot manufacture a
        # new persistent person that then contaminates all subsequent files.
        for group, vector in pooled.items():
            if group not in mapping:
                mapping[group] = confident_match(vector, updated)
        identified = []
        for start, end, local in turns:
            identity = mapping.get(aliases.get(local))
            if identified and identified[-1][1] == start and identified[-1][2] == identity:
                identified[-1] = (identified[-1][0], end, identity)
            else:
                identified.append((start, end, identity))
        return identified, updated
