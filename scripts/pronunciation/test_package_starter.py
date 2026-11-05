import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from package_starter import package


class PackageStarterTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp_parent = Path(__file__).resolve().parents[2] / "build" / "bundle-tests"
        cls.temp_parent.mkdir(parents=True, exist_ok=True)

    def prepare(self, directory):
        data = b"ID3" + bytes(253)
        (directory / "sample.mp3").write_bytes(data)
        (directory / ".env").write_text("PRIVATE=must-not-enter-bundle")
        root = {"locale": "en-GB", "voiceId": "test", "entries": [
            {"wordId": str(index), "wordRevisionId": "local-only", "lemma": f"word{index}",
             "file": "sample.mp3", "durationMs": 1200,
             "sha256": hashlib.sha256(data).hexdigest()}
            for index in range(59)]}
        manifest = directory / "manifest.json"
        manifest.write_text(json.dumps(root))
        return manifest

    def test_bundle_excludes_other_files_and_local_revision_foreign_keys(self):
        with tempfile.TemporaryDirectory(dir=self.temp_parent) as temporary:
            directory = Path(temporary)
            manifest = self.prepare(directory)
            archive_path = directory / "starter.zip"
            package(manifest, archive_path)
            with zipfile.ZipFile(archive_path) as archive:
                self.assertEqual(set(archive.namelist()), {"manifest.json", "sample.mp3"})
                root = json.loads(archive.read("manifest.json"))
                self.assertEqual(root["bundleType"], "masterenglish-starter-v1")
                self.assertNotIn("wordRevisionId", root["entries"][0])

    def test_bad_checksum_does_not_create_archive(self):
        with tempfile.TemporaryDirectory(dir=self.temp_parent) as temporary:
            directory = Path(temporary)
            manifest = self.prepare(directory)
            (directory / "sample.mp3").write_bytes(b"ID3" + bytes(254))
            with self.assertRaisesRegex(ValueError, "Checksum"):
                package(manifest, directory / "starter.zip")
            self.assertFalse((directory / "starter.zip").exists())


if __name__ == "__main__":
    unittest.main()
