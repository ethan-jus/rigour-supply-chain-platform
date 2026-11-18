"""Contextual ASR windows and timestamp-based, non-overlapping display phrases."""
import math


def speech_windows(spans, duration):
    regions = []
    for start, end, _ in spans:
        start, end = max(0, start-200), min(duration, end+200)
        if regions and start <= regions[-1][1] + 500:
            regions[-1][1] = max(end, regions[-1][1])
        else:
            regions.append([start, end])
    for start, end in regions:
        for begin in range(start, end, 30000):
            finish = min(begin+30000, end)
            yield begin, finish, max(start, begin-2000), min(end, finish+2000)


def timed_words(result, offset, begin, finish, duration):
    if not result:
        return []
    prediction = result[0]
    words = prediction.get("preds", "").split()
    timings = prediction.get("timestamp", [])
    if len(words) != len(timings):
        raise ValueError("ASR_TIMESTAMP_INVALID")
    selected = []
    previous = 0
    for word, timing in zip(words, timings):
        if (len(timing) != 2 or not all(math.isfinite(v) for v in timing)
                or timing[0] < previous or timing[1] <= timing[0]):
            raise ValueError("ASR_TIMESTAMP_INVALID")
        previous = timing[1]
        start, end = offset + round(timing[0]), min(duration, offset + round(timing[1]))
        # Overlap is recognition context only; every word has exactly one owner.
        if begin <= (start+end)/2 < finish and end > start:
            selected.append((start, end, word))
    return selected


def phrases(words):
    result = []
    for start, end, word in words:
        if (not result or start-result[-1][-1][1] >= 200
                or end-result[-1][0][0] > 5000
                or (result[-1][-1][2][-1] in "，。？！、"
                    and result[-1][-1][1]-result[-1][0][0] >= 1000)):
            result.append([])
        result[-1].append((start, end, word))
    return result


def punctuate(words, model):
    if not words:
        return words
    original = "".join(w[2] for w in words)
    rendered = model(join_words([w[2] for w in words]))[0]
    boundaries = {}
    offset = 0
    result = [list(w) for w in words]
    for index, word in enumerate(words):
        offset += len(word[2])
        boundaries[offset] = index
    consumed = 0
    for char in rendered:
        if consumed < len(original) and char == original[consumed]:
            consumed += 1
        elif char.isspace():
            continue
        elif char in "，。？！、,.?!":
            if consumed in boundaries:
                result[boundaries[consumed]][2] += char
        else:
            raise ValueError("ASR_PUNCTUATION_CHANGED_TEXT")
    if consumed != len(original):
        raise ValueError("ASR_PUNCTUATION_CHANGED_TEXT")
    return result


def join_words(words):
    result = ""
    for word in words:
        if result and result[-1].isascii() and result[-1].isalnum() and word[0].isascii() and word[0].isalnum():
            result += " "
        result += word
    return result
