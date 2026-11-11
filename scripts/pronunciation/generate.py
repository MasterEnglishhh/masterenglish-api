"""Generate reusable MP3 pronunciation files. No R2 credentials enter this container."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request
import wave

VOICE = "en_GB-cori-high"
REVISION = "e7229f7d33a10e145e109b886d661984201ac3c4"
MODEL_SHA256 = "470b4dd634c98f8a4850d7626ffc3dfc90774628eeef6605a6dd8f88f30a5903"
VOICE_PATH = "en/en_GB/cori/high"
BASE = f"https://huggingface.co/rhasspy/piper-voices/resolve/{REVISION}/{VOICE_PATH}"
CARD = f"https://huggingface.co/rhasspy/piper-voices/blob/{REVISION}/{VOICE_PATH}/MODEL_CARD"


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    words = json.loads(args.input.read_text(encoding="utf-8-sig"))
    args.output.mkdir(parents=True, exist_ok=True)
    model_dir = args.output / "model"
    model_dir.mkdir(exist_ok=True)
    for filename in [f"{VOICE}.onnx", f"{VOICE}.onnx.json", "MODEL_CARD"]:
        destination = model_dir / filename
        if not destination.exists():
            print(f"Downloading {filename}", flush=True)
            temporary = destination.with_suffix(destination.suffix + ".partial")
            with urllib.request.urlopen(f"{BASE}/{filename}", timeout=300) as response:
                with temporary.open("wb") as output:
                    while chunk := response.read(1024 * 1024):
                        output.write(chunk)
            temporary.replace(destination)
    if sha256(model_dir / f"{VOICE}.onnx") != MODEL_SHA256:
        raise ValueError("Voice model checksum mismatch; remove the corrupt model and download again.")
    from piper import PiperVoice
    voice = PiperVoice.load(str(model_dir / f"{VOICE}.onnx"))
    entries = []
    for word in words:
        revision = word["wordRevisionId"]
        mp3 = args.output / f"{revision}.mp3"
        if not mp3.exists():
            wav = args.output / f"{revision}.wav"
            with wave.open(str(wav), "wb") as output:
                voice.synthesize_wav(word["lemma"] + ".", output)
            subprocess.run([
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-i", str(wav), "-ac", "1", "-codec:a", "libmp3lame",
                "-b:a", "96k", "-af", "loudnorm=I=-18:TP=-1.5:LRA=11",
                str(mp3),
            ], check=True)
            wav.unlink()
        probe = subprocess.run([
            "ffprobe", "-v", "error", "-show_entries", "format=duration",
            "-of", "json", str(mp3),
        ], check=True, capture_output=True, text=True)
        duration = round(float(json.loads(probe.stdout)["format"]["duration"]) * 1000)
        if not 100 <= duration <= 30000:
            raise ValueError(f"Invalid pronunciation duration: {word['lemma']}")
        entries.append({**word, "file": mp3.name, "durationMs": duration, "sha256": sha256(mp3)})
        print(f"Generated {word['lemma']} ({duration}ms)", flush=True)
    manifest = {
        "voiceId": VOICE, "locale": "en-GB", "modelRevision": REVISION,
        "modelSha256": sha256(model_dir / f"{VOICE}.onnx"), "sourceUrl": CARD,
        "licenseCode": "PIPER_MODEL_CARD_PUBLIC_DOMAIN_DATASET",
        "attribution": "Synthetic speech generated locally with Piper 1.8.0, en_GB-cori-high. "
                       "Voice by Bryce Beattie, trained on public-domain LibriVox recordings. "
                       "This is computer-generated pronunciation, not a human dictionary recording.",
        "entries": entries,
    }
    (args.output / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Completed {len(entries)} MP3 files.", flush=True)


if __name__ == "__main__":
    main()
