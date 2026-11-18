import unittest
from types import SimpleNamespace
from unittest.mock import Mock
import numpy as np
from speakers import SpeakerDiarizer, assign_speakers, exclusive_turns, validate_profiles, confident_match


def voice(index):
    vector = np.zeros(192, dtype=np.float32)
    vector[index] = 1
    return vector


class SpeakerTest(unittest.TestCase):
    def test_same_voices_keep_ids_after_five_minute_boundary_and_new_voice_gets_c(self):
        first, state = assign_speakers({7:voice(0), 3:voice(1)}, [])
        self.assertEqual(first, {7:0, 3:1})
        # Local model labels and speaking order can both change in the next file.
        second, updated = assign_speakers({99:voice(1), 5:voice(0), 8:voice(2)}, state)
        self.assertEqual(second, {99:1, 5:0, 8:2})
        self.assertEqual(len(state), 2)  # input remains reusable after failed persistence/retry
        self.assertEqual(len(updated), 3)
        self.assertEqual(assign_speakers({99:voice(1), 5:voice(0), 8:voice(2)}, state)[0], second)
        self.assertEqual(assign_speakers({99:voice(1)}, [])[0], {99:0})  # another visit starts anew

    def test_two_distinct_local_voices_cannot_take_one_existing_id(self):
        _, state = assign_speakers({0:voice(0)}, [])
        mapping, _ = assign_speakers({4:voice(0), 5:voice(0)}, state)
        self.assertNotEqual(mapping[4], mapping[5])

    def test_overlap_is_unassigned_and_audio_is_not_duplicated(self):
        turns=[SimpleNamespace(start=0,end=3,speaker=0),SimpleNamespace(start=2,end=4,speaker=1)]
        self.assertEqual(exclusive_turns(turns,4000),[(0,2000,0),(2000,3000,None),(3000,4000,1)])

    def segment_vectors(self, vectors, durations=None):
        diarizer = SpeakerDiarizer.__new__(SpeakerDiarizer)
        turns = [SimpleNamespace(start=i*5, end=i*5+(durations[i] if durations else 4), speaker=i) for i in range(len(vectors))]
        diarizer.diarizer = Mock()
        diarizer.diarizer.process.return_value.sort_by_start_time.return_value = turns
        diarizer.extractor = Mock()
        diarizer.extractor.is_ready.return_value = True
        diarizer.extractor.compute.side_effect = vectors
        return diarizer.segments(np.zeros(len(vectors)*5*16000, dtype=np.float32), [])

    def test_similar_distinct_voices_are_not_pooled_into_one_identity(self):
        # Regression: two locally separated voices with cosine 0.619 were merged,
        # contaminating a registered speaker's otherwise matching signature.
        other = voice(0)*0.619 + voice(1)*np.sqrt(1-0.619**2)
        turns, profiles = self.segment_vectors([voice(0), other])
        self.assertEqual([t[2] for t in turns], [0, 1])
        self.assertEqual(len(profiles), 2)

    def test_strong_fragments_of_same_voice_share_identity(self):
        repeat = voice(0)*0.9 + voice(1)*np.sqrt(1-0.9**2)
        turns, profiles = self.segment_vectors([voice(0), repeat])
        self.assertEqual([t[2] for t in turns], [0, 0])
        self.assertEqual(len(profiles), 1)

    def test_intermediate_voice_cannot_chain_two_distinct_identities(self):
        # Both adjacent pairs score 0.8, but the endpoints score only 0.28.
        middle = voice(0)*0.8 + voice(1)*0.6
        last = voice(0)*0.28 + voice(1)*0.96
        turns, _ = self.segment_vectors([voice(0), middle, last])
        self.assertNotEqual(turns[0][2], turns[-1][2])

    def test_invalid_or_nonfinite_context_is_rejected(self):
        for profiles in [[{'id':1,'embedding':voice(0).tolist()}], [{'id':0,'embedding':[float('nan')]*192}], [{}]]:
            with self.assertRaises(ValueError): validate_profiles(profiles)

    def test_brief_noise_does_not_create_c_but_sustained_third_voice_does(self):
        turns, profiles = self.segment_vectors([voice(0),voice(1),voice(2),voice(3)], [4,4,1.6,2.9])
        self.assertEqual([t[2] for t in turns], [0,1,None,None])
        self.assertEqual(len(profiles),2)
        turns, profiles = self.segment_vectors([voice(0),voice(1),voice(2)])
        self.assertEqual([t[2] for t in turns], [0,1,2])
        self.assertEqual(len(profiles),3)

    def test_ambiguous_phrase_never_gets_the_closest_person_by_default(self):
        profiles=[{"id":0,"embedding":voice(0)},{"id":1,"embedding":voice(1)}]
        self.assertIsNone(confident_match((voice(0)+voice(1))/np.sqrt(2),profiles))
        self.assertIsNone(confident_match(voice(2),profiles))
        self.assertEqual(confident_match(voice(1),profiles),1)

    def test_turn_interior_support_cannot_override_a_clear_different_voice(self):
        diarizer = SpeakerDiarizer.__new__(SpeakerDiarizer)
        profiles=[{"id":0,"embedding":voice(0)},{"id":1,"embedding":voice(1)}]
        diarizer.embedding = Mock(return_value=voice(1))
        self.assertEqual(diarizer.identify(np.zeros(32000),profiles,0),1)
        diarizer.embedding.return_value=(voice(0)+voice(1))/np.sqrt(2)
        self.assertIsNone(diarizer.identify(np.zeros(32000),profiles,0))
        # Short responses at a turn boundary have no interior support.
        self.assertIsNone(diarizer.identify(np.zeros(8000),profiles))
        self.assertEqual(diarizer.identify(np.zeros(8000),profiles,0),0)

if __name__ == '__main__': unittest.main()
