# Hermes Mobile

A premium, native Android client for [Hermes Agent](https://github.com/NousResearch/hermes-agent).
Kotlin + Jetpack Compose, AMOLED-first, built around one rule: **the Hermes
VPS is the source of truth** — Desktop and Android are two views of the same
sessions.

## Status — Milestone 1

- Secure connection to a Hermes gateway (token auth; dashboard password →
  cookie → ws-ticket client included for gated binds)
- Profile discovery + fast profile switcher (strict isolation)
- Session list: search, rename, delete, pinned state, source badges,
  running indicator
- Open any existing conversation with full transcript (Desktop sessions
  included), live streamed responses, tool/reasoning activity rows,
  approval prompts with big obvious buttons
- Model / thinking level / Fast Mode controls that change the real session
- Per-session drafts, offline banner, automatic reconnect with jittered
  backoff, foreground reconciliation
- Fold/tablet adaptive list-detail layout; single-pane on phones/cover screen

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the design and
[`docs/UPSTREAM.md`](docs/UPSTREAM.md) for the pinned Hermes protocol contract.

## Building

Requirements: JDK 17+, Android SDK (platform 36).

```sh
cd android
./gradlew :app:assembleDebug        # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :protocol:test            # protocol contract tests
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Connecting

1. Launch the app → enter your gateway URL (e.g. `http://your-vps:9119` over
   Tailscale, or an HTTPS reverse-proxy URL) and the gateway token.
2. Test & Connect → pick a profile → chat.

Note upstream auth rules: loopback binds only accept loopback peers, and
non-loopback binds require an auth provider (password/OAuth). Over Tailscale
the practical setups are an HTTPS/gated bind, or a tunnel that terminates on
the VPS loopback.

## Layout

```
android/
  protocol/   pure-JVM Hermes compatibility layer (wire + reducers + tests)
  app/        Compose UI, settings, Keystore-backed credential storage
  docs/       ARCHITECTURE.md, UPSTREAM.md
  scripts/    audit-upstream.sh
```
