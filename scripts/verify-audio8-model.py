"""Real pinned weights on the host CPU; this does not execute Android CompiledModel or measure phones."""
import argparse
import json
from pathlib import Path
import sys
import time
import wave
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tools/audio8"))
from audio8_tts_litert import Audio8LiteRT, SR

parser = argparse.ArgumentParser()
parser.add_argument("model", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
model = Audio8LiteRT(str(args.model), threads=4)
voice = json.loads((args.model / "voice/meta.json").read_text())
codes = np.load(args.model / "voice/codes.npy").astype(np.int64)
report = {"runtime": "Python reference loop / ai-edge-litert Interpreter CPU; not Android performance", "samples": []}
for i, text in enumerate(["傍晚的雨停了，他终于松了一口气。", "他轻声说，Good evening，外面的雨停了。"]):
    assert len(model._enc(text)) <= 49
    started = time.monotonic()
    generated = model.generate_codes(text, voice["reference_text"], codes, seed=42, max_new_tokens=512)
    assert 0 < generated.shape[1] < 512, "empty or capped speech"
    pcm = model.decode_audio(generated)
    assert np.isfinite(pcm).all() and np.max(np.abs(pcm)) > .01
    assert .5 < len(pcm) / SR < 24
    path = args.output / f"sample-{i}.wav"
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(SR)
        wav.writeframes(np.rint(np.clip(pcm, -1, 1) * 32767).astype("<i2").tobytes())
    report["samples"].append({"text": text, "frames": int(generated.shape[1]), "audio_s": len(pcm) / SR,
                              "host_s": time.monotonic() - started})
    print(report["samples"][-1], flush=True)
(args.output / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
