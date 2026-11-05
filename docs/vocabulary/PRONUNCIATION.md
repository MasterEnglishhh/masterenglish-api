# MP3 pronunciation on R2

Pronunciation is generated once on the developer's computer using Piper 1.8.0
and the British English `en_GB-cori-high` voice. FFmpeg encodes mono MP3 files.
No paid TTS account or new secret is needed. R2 storage/requests still follow
the account's existing billing; this is not a promise of free hosting.

The generator never receives R2 credentials. The backend importer uses the
existing `R2_ENABLED=true`, `R2_ENDPOINT`, `R2_REGION`, `R2_BUCKET`,
`R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` settings in the API `.env`.
Use the media bucket. The importer rejects bucket names containing `backup`.
Do not put keys in the frontend, manifest or Git.

## Generate on local

Start `masterenglish-local-postgres-1` and ensure the word/deck fixtures exist.
From the API repository:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/pronunciation/prepare.ps1
```

Files, model and manifest stay in ignored `build/pronunciations/`. The model
revision and SHA256 are pinned in `generate.py`. The first run downloads the
model and tool dependencies; existing MP3 files can be reused on later runs.
Removing `build/` also removes this local cache, so keep a separate copy of the
manifest and MP3 files if you need them after cleaning the build directory.

## Import on local

Use an ACTIVE local content administrator's UUID, provisioned separately.
The runner does not create administrators or change learner permissions:

```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=local --app.vocabulary.pronunciation.import-path=build/pronunciations/manifest.json --app.vocabulary.pronunciation.actor=<LOCAL-CONTENT-ADMIN-UUID>"
```

The runner is available only in profile `local` and is off by default. It
validates word/revision/lemma, file path, MP3 header, size, duration and SHA256;
uploads to `pronunciation/<locale>/<voice>/<sha256>.mp3`; verifies object size
and checksum metadata with HEAD, downloads it to compare SHA256; then records
READY media and the binding.
An interrupted import can leave unbound immutable R2 objects. Re-running with
the same manifest reuses the same object keys. It does not overwrite an existing
word-revision/locale binding or regenerate already linked pronunciation.
Database links are committed together after a successful import.

Migration V8 adds `word_pronunciations`. Each file belongs to an exact word
revision; previously published WORD/DECK snapshots and SRS history stay intact.
New word revisions need their own pronunciation binding. Audio can be archived
through the media lifecycle; archived/revoked words cannot issue new links.

## Playback

Vocabulary word list/detail and flashcard DTOs return a relative `audioUrl`
when the exact revision has a READY audio asset in the configured bucket.
The frontend obtains a fresh playback ticket using the same auth/refresh flow,
then the HTML audio element plays directly from R2. Ticket responses and the
redirect endpoint are authenticated for LEARNER and use `Cache-Control: no-store`.
Private R2 download links expire after five minutes; an already issued link
can work until that expiry even after content is revoked.

No public R2 bucket, `r2.dev` URL or public custom domain is required. Native
HTML audio playback does not need a CORS policy; future JS fetch/Web Audio
processing would need its own explicit CORS configuration.

The player has normal/slow playback, native controls and an error/retry path.
Changing words stops the previous sound. It never falls back silently to the
browser's synthetic voice. It labels these recordings as synthetic British
English. The current slice pronounces headwords, not full example sentences.

## Sources and notices

- Engine: [Piper](https://github.com/OHF-Voice/piper1-gpl), GPL-3.0-or-later,
  used as a separate local generation tool; not embedded in the web application.
- Voice: [en_GB-cori-high model card](https://huggingface.co/rhasspy/piper-voices/blob/e7229f7d33a10e145e109b886d661984201ac3c4/en/en_GB/cori/high/MODEL_CARD).
  Bryce Beattie trained the voice from public-domain LibriVox recordings.
- The model card and generator identity are retained with the local manifest;
  attribution is also stored in `content_sources` for the imported audio.
- Generated word pronunciations need listening review before adopting them
  as final teaching content, especially words with different noun/verb stress.

Production deployment/import is separate from this local operation. Use the
explicit [production import guide](PRODUCTION_IMPORT.md) for the one-shot
`--content-import` command; do not enable the local profile on EC2. Audio
objects may be in the existing media R2 bucket, but this import writes only the
local database. EC2 accounts, schema and content are not changed by this runner.

## Local verification — 2026-11-05

- Generated/imported **59 MP3 files**, total **666,495 bytes**. All 59 uploads
  were downloaded again and checked against their local SHA256 before commit.
- Local database has 59 pronunciation bindings and 59 READY audio assets;
  existing six decks, word snapshots and learner permissions were preserved.
- Full backend `check` passed; after the importer flush regression fix,
  all **6 pronunciation integration tests** passed. These cover learner access,
  no-store tickets, revoked words, archived media, checksum rejection and repeat import.
- Frontend lint, **63 tests** and Cloudflare Pages static build passed.
- Real local HTTP probe logged in a disposable learner, read six decks,
  verified audio links on a deck's ten cards, obtained an airport playback ticket,
  downloaded the private R2 MP3 and matched its SHA256. This is an API/download
  check, not a browser listening-quality review.
- The disposable learner/sessions were removed; the import actor was disabled
  and its role removed. Temporary API processes were stopped. Restart the API
  in IntelliJ and the frontend dev server to load the new code and local R2 setting.
