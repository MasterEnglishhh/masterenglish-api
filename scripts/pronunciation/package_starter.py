"""Package only the reviewed manifest/MP3s; never credentials, models or a database."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import tempfile
import zipfile


def package(manifest_path: Path, output: Path):
    manifest_path = manifest_path.resolve(strict=True)
    root = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    entries = root.get("entries", [])
    if len(entries) != 59 or root.get("locale") != "en-GB":
        raise ValueError("Expected the complete 59-word en-GB starter recording set.")
    seen = set()
    files = {}
    portable = []
    for entry in entries:
        if entry["wordId"] in seen:
            raise ValueError("Duplicate word in manifest.")
        seen.add(entry["wordId"])
        name = entry["file"]
        if not re.fullmatch(r"[a-zA-Z0-9_-]+\.mp3", name):
            raise ValueError("Expected a plain MP3 filename.")
        source = (manifest_path.parent / name).resolve(strict=True)
        if source.parent != manifest_path.parent:
            raise ValueError("MP3 must stay inside the manifest directory.")
        data = source.read_bytes()
        if hashlib.sha256(data).hexdigest() != entry["sha256"]:
            raise ValueError(f"Checksum mismatch: {name}")
        if not 128 <= len(data) <= 20 * 1024 * 1024:
            raise ValueError("Invalid MP3 size.")
        if not (data.startswith(b"ID3") or (data[0] == 255 and data[1] & 224 == 224)):
            raise ValueError("Invalid MP3 header.")
        if not 100 <= entry["durationMs"] <= 30000:
            raise ValueError("Invalid MP3 duration.")
        files[name] = data
        # Local revision IDs never become production foreign keys.
        portable.append({key: value for key, value in entry.items() if key != "wordRevisionId"})
    root["entries"] = portable
    root["bundleType"] = "masterenglish-starter-v1"
    if output.exists():
        raise FileExistsError("Output already exists; choose another bundle filename.")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as temporary:
        archive_path = Path(temporary) / "bundle.zip"
        with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("manifest.json", json.dumps(root, ensure_ascii=False, indent=2))
            for name, data in files.items():
                archive.writestr(name, data)
        archive_path.rename(output)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    print(f"BUNDLE={output.resolve()}")
    print(f"WORDS={len(entries)} SHA256={digest}")
    return digest


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=Path("build/pronunciations/manifest.json"))
    parser.add_argument("--output", type=Path, default=Path("build/content-import/starter-v1.zip"))
    options = parser.parse_args()
    package(options.manifest, options.output)
