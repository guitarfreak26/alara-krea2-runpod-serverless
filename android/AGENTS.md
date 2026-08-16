# AGENTS.md — how AI workers operate in this repository

This is **Hermes Mobile / alara-agent-mobile**: a native Android client
(Kotlin, Jetpack Compose, Material 3) for a self-hosted Hermes Agent VPS.
It applies to Claude, Codex, Cursor and any other agent working here.

## Ground rules

- **The app is a client.** The OVH VPS is the only always-on Hermes runtime.
  Never add code that makes the app act as an agent, gateway, or writer of
  server state beyond the documented REST/SSE surface.
- **Never commit secrets.** No bearer tokens, keystores, `.env`, server
  hostnames with credentials, or `local.properties`. The gateway token is
  entered at runtime and stored AES/GCM-encrypted via Android Keystore
  (`security/CryptoBox.kt`). Every user-facing error passes `redact()`.
- **Bearer in headers only.** Authorization headers, never query params,
  never logs. `Redaction.kt` strips `token=`/`key=` params and Bearer values.
- **Server is the source of truth.** Session IDs are canonical server IDs;
  the cache is never authoritative; do not invent local stores that shadow
  the VPS (`docs/ARCHITECTURE.md`).
- **Capability-gate everything.** New server features are discovered via
  `GET /v1/capabilities` `features.*` and degrade cleanly on older builds
  (`docs/UPSTREAM.md` pins the wire contracts — update it when the
  contract changes, and run `scripts/audit-upstream.sh`).
- **Don't spend real credits in tests.** No test may submit a real
  generation, post content, or message subscribers.

## Layout

- `protocol/` — pure-JVM Hermes protocol module (no Android deps).
  `ApiServerGateway` (port 8642 REST+SSE surface) is the implementation in
  production use; `HermesLiveGateway` covers the dashboard WS surface.
  All wire-contract logic and its tests live here.
- `app/` — Compose UI, manual DI via `AppContainer` (`HermesApp.kt`).
  `ui/HomeViewModel.kt` is the single state holder.
- `docs/` — architecture, pinned upstream contracts, Bot Mode reference.

## Build & test

    ./gradlew :protocol:test          # protocol unit tests (JVM, fast)
    ./gradlew :app:assembleDebug      # debug APK
    ./gradlew :app:assembleRelease    # release (minified; needs signing config)

SDK path comes from `local.properties` (gitignored). Tests must stay green;
add protocol tests for any wire-contract change.

## Conventions

- Kotlin official style; Compose; no new DI framework; no new databases.
- Comments explain constraints, not narration.
- Version bumps: increment `versionCode`/`versionName` in
  `app/build.gradle.kts` for every user-visible build.
