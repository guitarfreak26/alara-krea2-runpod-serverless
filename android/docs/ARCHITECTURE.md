# Hermes Mobile — Architecture Decision Record

Status: accepted · Scope: Milestone 1 foundation for the native Android Hermes client.

## 1. Canonical protocol

**The official hermes-agent gateway contract is canonical.** One backend process
(`hermes serve` / `hermes dashboard`, default port 9119) exposes:

- **JSON-RPC 2.0 over a single WebSocket at `/api/ws`** — conversation control,
  streaming events, approvals, models, config. Requests are
  `{jsonrpc, id, method, params}`; server events are frames with
  `method: "event"` and `params: {type, session_id, payload}`.
- **REST under `/api/`** — rich session list (`GET /api/sessions`), paged
  transcripts (`GET /api/sessions/{id}/messages`), profiles, rename/pin/archive
  (`PATCH /api/sessions/{id}`), ws-ticket minting.

Everything wire-specific lives in the `:protocol` module (the "Hermes
compatibility layer"). The UI depends only on `HermesGateway` /
`SessionHandle` / `TimelineState`. When the Hermes protocol moves, only
`:protocol` changes. Exact upstream citations are pinned in
[`UPSTREAM.md`](UPSTREAM.md).

Community clients were audited as references, not forked:

- **luinbytes/hermes-android** (Kotlin, MIT) — protocol layer patterns adapted
  with attribution (JSON-RPC framing, request correlation, connection
  generation guards, dashboard cookie handling). Its god-repository/god-state
  architecture was deliberately not carried forward.
- **rusty4444/hermes-android** (Flutter, license-ambiguous) — used strictly as
  a behavioral spec (never-auto-resubmit, terminal-event-not-ack, event
  quirks). No code copied.

## 2. Native Android architecture

- Kotlin 2.1 · Jetpack Compose · Material 3 (heavily customized, AMOLED-first)
  · Material 3 Adaptive for the Fold/tablet list-detail layout
  · coroutines/Flow · OkHttp WebSocket · kotlinx-serialization
  · DataStore + Android Keystore. No WebView, no chat SDK.
- **Modules:** `:protocol` (pure JVM — testable without an emulator) and
  `:app` (Compose UI, manual DI via `AppContainer`; Hilt deliberately omitted
  at this size).
- State: `HomeViewModel` orchestrates connection, profiles, session list and
  the one open `SessionHandle`; streaming renders straight from the handle's
  `StateFlow<TimelineState>`. Reducers (`TimelineReducer`) are pure and
  unit-tested — the sync behavior is testable without a device.

## 3. Sync architecture (Desktop ↔ Android)

**The Hermes VPS is the only source of truth. The app rehydrates; it never
replays or mirrors.**

- Stored session ids (durable) are the identity used everywhere; runtime ids
  are cached only for the lifetime of one socket and dropped on reconnect
  (the backend recycles them).
- History identity = durable `row_id` from the backend; streamed turns use a
  local generation counter and are **replaced wholesale** by authoritative
  history on resume/refresh (the gateway has no event replay or sequence
  numbers, so replay is impossible by design).
- Reconnect = jittered exponential backoff owned by the transport
  (1–30 s, indefinite while desired), re-minting the single-use 30 s ws-ticket
  before every dial; on connect, every open session re-runs `session.resume`
  and merges the `inflight` snapshot (partial assistant text, queued prompt).
- `prompt.submit` is **never auto-resubmitted**. An ambiguous ack re-syncs
  state and leaves retry to the user.
- Cross-client liveness: the gateway binds the live stream to the submitting
  client, so a Desktop-driven turn does not stream here. The session shows
  `running`, and the open conversation polls authoritative history at a low
  rate (6 s) until the turn settles; `sessions.changed` broadcasts and
  foreground reconciliation cover the list.
- The local cache (DataStore) stores connection settings, drafts and
  preferences only — never conversation truth.

## 4. Notification architecture

Milestone 1 ships foreground streaming only. The plan (M4):

1. While connected, map `notification.show`, `approval.request`,
   `clarify.request` and turn completion to local notifications with channels
   (completions / approvals / failures / automations) — same event surface the
   desktop uses.
2. The gateway has **no push transport** (no FCM/APNs upstream). For
   backgrounded reliability we will add the smallest possible companion
   service on the VPS (webhook subscriber → UnifiedPush/ntfy topic), carrying
   only opaque session/event ids; content is fetched over the authenticated
   channel after wake. No third-party chat platform required.

## 5. Profile architecture

Profiles are real isolated agents (separate `HERMES_HOME` per profile).
The app treats them exactly as the backend does:

- Discovery via `GET /api/profiles`; the active profile is an app-level
  selection persisted locally.
- Every session list, search, create, resume and delete call carries the
  explicit `profile` parameter — profile is never inferred server-side, so a
  session can never silently land on the wrong profile.
- Switching profiles closes the open conversation and reloads sessions;
  histories and state are never merged.

## 6. Foldable / tablet strategy

Material 3 Adaptive `NavigableListDetailPaneScaffold`:

- Compact width (phone, Fold cover): single pane — list or conversation,
  with predictive back.
- Expanded width (Fold unfolded, tablet, landscape): persistent session list
  left, conversation right.
- State (scroll, draft, stream) lives in ViewModel/DataStore, so posture
  changes, rotation and split-screen never drop it. A third supporting pane
  (tool activity / artifacts) is planned for very wide layouts in M5.

## 7. Milestone 1 scope shipped

Secure connect (token now; password→cookie→ws-ticket client implemented for
gated backends), profile discovery/switching, session list
(search/rename/delete/pin state), full transcript open, send + live streaming,
tool/reasoning/approval rows, model/thinking/fast-mode controls, drafts per
session, reconnect + foreground reconciliation, AMOLED theme, adaptive
two-pane. Contract tests cover the reducer (streaming, dedup, rehydration),
socket (correlation, failure, reconnect) and REST parsing.
