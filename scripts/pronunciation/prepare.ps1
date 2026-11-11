$ErrorActionPreference = 'Stop'
$pronunciationRepo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$pronunciationOutput = Join-Path $pronunciationRepo 'build/pronunciations'
New-Item -ItemType Directory -Path $pronunciationOutput -Force | Out-Null
$pronunciationSql = @'
SELECT coalesce(jsonb_agg(jsonb_build_object('wordId',w.id,'wordRevisionId',w.published_revision_id,'lemma',w.lemma) ORDER BY w.lemma),'[]'::jsonb)
FROM words w JOIN content_items i ON i.id=w.id WHERE NOT i.is_archived AND i.access_revoked_at IS NULL;
'@
$pronunciationWords = $pronunciationSql | docker exec -i masterenglish-local-postgres-1 sh -c 'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -X -A -t -v ON_ERROR_STOP=1'
if ($LASTEXITCODE -ne 0) { throw 'Cannot read MasterEnglish local vocabulary.' }
[System.IO.File]::WriteAllText((Join-Path $pronunciationOutput 'words.json'), ($pronunciationWords -join "`n"), [System.Text.UTF8Encoding]::new($false))
docker build -t masterenglish-pronunciation:piper-1.8.0 $PSScriptRoot
if ($LASTEXITCODE -ne 0) { throw 'Cannot build the pronunciation generator.' }
docker run --rm --name masterenglish-local-pronunciation-generate --cpus 4 --memory 3g --mount "type=bind,source=$pronunciationOutput,target=/work" masterenglish-pronunciation:piper-1.8.0 --input /work/words.json --output /work
if ($LASTEXITCODE -ne 0) { throw 'Pronunciation generation failed.' }
Write-Output "MP3 files and manifest: $pronunciationOutput"
