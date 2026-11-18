import subprocess
import unittest
from unittest.mock import patch

import numpy as np
import server
from alignment import speech_windows, timed_words, phrases, join_words, punctuate


class SpeechWindowTest(unittest.TestCase):
    def setUp(self):
        server.app.state.punctuation = lambda text: (text, [])

    def test_ten_ms_tail_never_reaches_model_with_zero_acoustic_frames(self):
        waveform = np.zeros(480160, dtype="<f4")  # 30.010 seconds
        server.app.state.speakers = unittest.mock.Mock()
        server.app.state.speakers.segments.return_value = ([(0, 30010, 0)], [])
        server.app.state.speakers.identify.return_value = 0
        sizes = []

        def recognize(chunk):
            sizes.append(len(chunk))
            if len(chunk) < 400:
                raise IndexError("no acoustic frame")
            return [{"preds": "测试 原文", "timestamp": [[0,1000],[1000,2000]]}]

        server.app.state.asr = recognize
        with patch("server.subprocess.run", return_value=subprocess.CompletedProcess([], 0, waveform.tobytes(), b"")):
            result = server.transcribe_bytes(b"fixture", [])
        self.assertEqual(sizes, [480160, 32160])
        self.assertEqual(result["transcript"], "测试原文")
        self.assertEqual(result["segments"][0]["endMs"], 2000)
        self.assertEqual(result["durationMs"], 30010)

    def test_no_speech_does_not_call_recognizer_or_invent_text(self):
        server.app.state.speakers = unittest.mock.Mock()
        server.app.state.speakers.segments.return_value = ([], [])
        with patch.object(server.app.state, "asr", create=True) as model, patch("server.subprocess.run", return_value=subprocess.CompletedProcess([], 0, np.zeros(16000, dtype="<f4").tobytes(), b"")):
            result = server.transcribe_bytes(b"fixture", [])
        model.assert_not_called()
        self.assertEqual(result["transcript"], "")
        self.assertEqual(result["segments"], [])

    def test_phrase_crossing_overlap_is_not_assigned_to_the_louder_person(self):
        server.app.state.speakers = unittest.mock.Mock()
        server.app.state.speakers.segments.return_value = ([(0,1000,0),(1000,2000,None),(2000,3000,1)], [])
        server.app.state.asr = lambda chunk: [{"preds":"同时讲话", "timestamp":[[500,2500]]}]
        with patch("server.decode_audio",return_value=np.zeros(48000)):
            result = server.transcribe_bytes(b"fixture",[])
        self.assertIsNone(result["segments"][0]["speakerId"])
        server.app.state.speakers.identify.assert_not_called()

    def test_speaker_boundary_does_not_cut_a_word_or_recognize_audio_twice(self):
        windows = list(speech_windows([(0,9380,0),(9380,9970,None),(9970,34000,1)],34000))
        self.assertEqual(windows, [(0,30000,0,32000),(30000,34000,28000,34000)])
        left = timed_words([{"preds":"张 松 下", "timestamp":[[29000,29500],[29500,30100],[30100,30500]]}],0,0,30000,34000)
        right = timed_words([{"preds":"张 松 下", "timestamp":[[1000,1500],[1500,2100],[2100,2500]]}],28000,30000,34000,34000)
        self.assertEqual(join_words([w[2] for w in left+right]), "张松下")

    def test_missing_timestamps_fail_instead_of_guessing_speaker_from_text(self):
        with self.assertRaisesRegex(ValueError, "ASR_TIMESTAMP_INVALID"):
            timed_words([{"preds":"你好 我叫张松"}],0,0,1000,1000)

    def test_phrase_boundaries_preserve_all_words_and_english_spacing(self):
        words=[(0,200,"你好"),(400,900,"张松"),(900,3000,"欢迎"),(3000,3500,"来"),(3500,4000,"CLUB")]
        grouped=phrases(words)
        self.assertEqual([w for phrase in grouped for w in phrase], words)
        self.assertEqual(join_words(["欢迎","来","Happy","CLUB"]),"欢迎来Happy CLUB")

    def test_punctuation_cannot_rewrite_names_or_add_content(self):
        words=[(0,400,"你好"),(400,1600,"张松")]
        self.assertEqual(punctuate(words,lambda text:("你好，张松。",[])),
                         [[0,400,"你好，"],[400,1600,"张松。"]])
        with self.assertRaisesRegex(ValueError,"ASR_PUNCTUATION_CHANGED_TEXT"):
            punctuate(words,lambda text:("你好，张三。",[]))


class VoiceEnrollmentTest(unittest.TestCase):
    def setUp(self):
        server.app.state.speakers = unittest.mock.Mock()
        self.waveform = np.zeros(20 * 16000, dtype="<f4")

    def test_single_speaker_returns_only_features_and_quality(self):
        features = [1.0] + [0.0] * 191
        server.app.state.speakers.segments.return_value = ([(0, 12000, 0)], [{"id": 0, "embedding": features}])
        result = server.enrollment(self.waveform)
        self.assertEqual(result["embedding"], features)
        self.assertEqual(result["speechMs"], 12000)
        self.assertEqual(set(result), {"model", "embedding", "speechMs"})

    def test_short_silent_or_multiple_speakers_require_another_recording(self):
        with self.assertRaises(server.HTTPException):
            server.enrollment(self.waveform[:16000])
        for spans, profiles in [([], []), ([(0, 6000, 0)], [{"id": 0}]),
                                ([(0, 12000, 0), (12000, 18000, 1)], [{"id": 0}, {"id": 1}])]:
            server.app.state.speakers.segments.return_value = (spans, profiles)
            with self.assertRaises(server.HTTPException) as error:
                server.enrollment(self.waveform)
            self.assertEqual(error.exception.detail, "VOICE_QUALITY")


if __name__ == "__main__":
    unittest.main()
