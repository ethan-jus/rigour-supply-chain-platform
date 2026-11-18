"""Download the published CPU quantized models; never export or train at startup."""
import argparse
import hashlib
import json
import tarfile
import urllib.request
from pathlib import Path
from modelscope import snapshot_download

MODELS = {
    "asr": "damo/speech_paraformer-large-vad-punc_asr_nat-zh-cn-16k-common-vocab8404-onnx",
    "punctuation": "damo/punc_ct-transformer_zh-cn-common-vocab272727-onnx",
}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    manifest = {}
    expected_models = json.loads(Path(__file__).with_name("models-manifest.json").read_text())
    for name, model in MODELS.items():
        path = snapshot_download(model, local_dir=str(args.directory / name))
        files = {}
        for f in Path(path).rglob("*"):
            if f.is_file() and not f.name.startswith("."):
                files[str(f.relative_to(path))] = hashlib.sha256(f.read_bytes()).hexdigest()
        manifest[name] = {"repository": model, "sha256": files}
        for filename, digest in expected_models[name]["sha256"].items():
            if files.get(filename) != digest:
                raise RuntimeError("Speech model checksum mismatch: " + name + "/" + filename)
    speaker = args.directory / "speaker"
    speaker.mkdir(parents=True, exist_ok=True)
    urllib.request.urlretrieve("https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx", speaker / "campplus.onnx")
    archive = speaker / "segmentation.tar.bz2"
    urllib.request.urlretrieve("https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2", archive)
    with tarfile.open(archive) as package:
        (speaker / "segmentation.onnx").write_bytes(package.extractfile("sherpa-onnx-pyannote-segmentation-3-0/model.onnx").read())
        (speaker / "LICENSE-segmentation").write_bytes(package.extractfile("sherpa-onnx-pyannote-segmentation-3-0/LICENSE").read())
    archive.unlink()
    expected = json.loads(Path(__file__).with_name("models-manifest.json").read_text())["speaker"]
    for name, digest in expected["sha256"].items():
        if hashlib.sha256((speaker / name).read_bytes()).hexdigest() != digest:
            raise RuntimeError("Speaker model checksum mismatch: " + name)
    manifest["speaker"] = expected
    (args.directory / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

if __name__ == "__main__":
    main()
