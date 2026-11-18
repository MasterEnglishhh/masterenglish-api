# Run the API locally on Windows

Use JDK 21 and Docker Desktop with Linux containers. Keep the API and infra
repositories next to each other under the same workspace directory.

## Prepare PostgreSQL

The infra repository contains `compose.local.yaml`. Its untracked `.env` contains
the database name, username, password, and host port. The default host port is
15432 and the container uses PostgreSQL 17.

From the infra repository, start PostgreSQL and wait for its health check:

```powershell
Set-Location D:\MasterEnglish\masterenglish-infra
docker compose --env-file .env -f compose.local.yaml up -d --wait --wait-timeout 120
```

## Run the API

From the API repository, activate the local profile:

```powershell
Set-Location D:\MasterEnglish\masterenglish-api
.\gradlew.bat bootRun --args="--spring.profiles.active=local"
```

The local profile reads the same `.env` as Compose, using Spring's properties
file import support. No password is stored in application source files. If the
infra repository is elsewhere, set `MASTERENGLISH_LOCAL_ENV` to the absolute
path of its `.env` file before running the command.

Flyway applies migrations from `src/main/resources/db/migration`. The initial
migration creates only the internal Modulith event publication registry.
Hibernate validates the schema; it does not create or update tables.

The server listens on `127.0.0.1:8080`. Authentication and business endpoints
are configured in subsequent implementation steps.

Gradle configures the API and test JVMs to use UTC. When running the main class
directly from an IDE, set the VM option `-Duser.timezone=UTC` and activate the
`local` Spring profile. When running a packaged jar, use:

```powershell
java -Duser.timezone=UTC -jar build/libs/masterenglish-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

## Use Redis for login and OTP rate limits

From the API repository, start the optional Redis service:

```powershell
docker compose --env-file .env -f compose.local.yaml --profile redis up -d --wait redis
```

Add `AUTH_RATE_LIMIT_BACKEND=redis` to the backend `.env` and restart the API.
The default URL is `redis://127.0.0.1:16379`. Override
`AUTH_RATE_LIMIT_REDIS_URL` when the API and Redis use a different address.
`AUTH_RATE_LIMIT_REDIS_TIMEOUT_MS` defaults to 1000 milliseconds.

Redis stores expiring rate-limit counters keyed by hashed email and IP.
Accounts, OTP challenges and sessions stay in PostgreSQL. In Redis mode, a
rate-limited request returns HTTP 429 with `Retry-After`. When Redis is
unavailable, affected requests return HTTP 503 with no automatic fallback.
The default backend remains PostgreSQL. Existing counters stay in their
original backend when you switch. For a shared deployment, use the same
Redis database and rate-limit secret across API instances.

## Run tests

```powershell
.\gradlew.bat test
```

The generated Spring Boot context test uses its own PostgreSQL 17 container.
It does not use the local database credentials or the local profile. Docker
Desktop must be running for this test.
