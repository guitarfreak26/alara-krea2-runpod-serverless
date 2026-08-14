# Upstream tracking

This app implements the Hermes gateway client contract. This file pins what we
rely on, where it came from, and how to re-audit when upstream moves.

Re-audit with [`scripts/audit-upstream.sh`](../scripts/audit-upstream.sh).

## Tracked upstreams

| Repo | Role | Audited ref |
|---|---|---|
| `NousResearch/hermes-agent` | canonical backend + desktop client | main @ 2026-08-14 |
| `luinbytes/hermes-android` | Kotlin community client (MIT) — protocol reference, some patterns adapted | main @ 2026-08-14 |
| `rusty4444/hermes-android` | Flutter community client — behavioral reference only (no license grant; no code copied) | main @ 2026-08-14 |

## Contracts we depend on (hermes-agent)

### Transport
- WS endpoint `/api/ws`: `hermes_cli/web_server.py:16302`, handler `tui_gateway/ws.py:286`.
- JSON-RPC frames: requests `{jsonrpc,id,method,params}`; events
  `{method:"event", params:{type, session_id, payload}}` — `tui_gateway/server.py:1599`,
  `apps/shared/src/json-rpc-gateway.ts:338-384`.
- `gateway.ready` greeting with `change_events` flag: `tui_gateway/ws.py:313-326`.
- Delta coalescing (~33 ms) preserves ordering: `tui_gateway/ws.py:53-60`.

### Auth
- Mode discovery: public `GET /api/status` → `auth_required`, `auth_flows`.
- Token mode (loopback binds): REST header `X-Hermes-Session-Token` / Bearer
  (`web_server.py:352,417-434`), WS `?token=` (`web_server.py:15211`).
  Loopback binds also enforce a loopback *peer* (`web_server.py:14999`).
- Gated mode (non-loopback binds): password login `POST /auth/password-login`
  → `hermes_session_*` cookies; WS via `POST /api/auth/ws-ticket`
  (single-use, TTL 30 s — `hermes_cli/dashboard_auth/ws_tickets.py:42`),
  re-minted before every dial. Native PKCE flow exists at
  `/auth/native/authorize|token|refresh` (`dashboard_auth/routes.py:858,911`) — not yet implemented here.
- WS close codes 4401 (bad credential) / 4403 (guard rejected).

### Sessions
- Runtime `session_id` (per-socket, recycled) vs durable `stored_session_id` /
  `session_key`: `tui_gateway/methods_session.py:14,306`.
- `session.resume` result incl. `inflight{user,assistant,streaming,error}`,
  `queued{user}`, `status: idle|starting|working|waiting`:
  `tui_gateway/server.py:8220,7820,8040`.
- REST list `GET /api/sessions` (limit ≤ 100, `profile`, `exclude_sources`,
  pinned back-fill): `hermes_cli/web_routers/sessions.py:53`.
- Transcript `GET /api/sessions/{id}/messages` (limit ≤ 500, paged):
  `web_routers/sessions.py:601`; stable message identity `row_id` (WS) /
  `id` (REST): `tui_gateway/server.py:7190`.
- Rename/pin/archive `PATCH /api/sessions/{id}`: `web_routers/sessions.py:683`.
- Delete: WS `session.delete` (durable id; `4023` when live) or REST DELETE.

### Chat turn
- `prompt.submit` (`tui_gateway/methods_prompt.py:257`): ack
  `{"status":"streaming"|"queued"|"redirected"|"steered"}` is NOT completion;
  completion is the `message.complete` event. Desktop uses a 30-min ack timeout.
- Event vocabulary consumed: `message.start|delta|interim|complete`,
  `thinking.delta`, `reasoning.delta|available`, `status.update`,
  `tool.generating|start|progress|complete`, `approval.request`,
  `clarify.request`, `sudo.request`, `secret.request`, `*.expire`,
  `subagent.*`, `session.info`, `session.title`, `sessions.changed`, `error`.
- Approvals: `approval.respond {session_id, choice}` (session-keyed);
  clarify/sudo/secret respond by `request_id` (`methods_prompt.py:1261-1333`).
- Busy semantics: server-side queue/steer/redirect (`server.py:7652`);
  the client never generates a duplicate turn.

### Config
- Per-session model: `config.set {key:"model", value:"<model> --provider <p> --session"}`
  (`server.py:10901`); handle `deferred`, `confirm_required`.
- Thinking: `config.set {key:"reasoning"}`; fast mode:
  `config.set {key:"fast", value:"fast"|"normal"}` (`server.py:10997`).
- Contract version: `session.info.desktop_contract` (currently 6,
  `tui_gateway/server.py:5181`).

### Profiles
- WS `profiles.list` / REST `GET /api/profiles`
  (`tui_gateway/methods_profiles.py:22`, `web_routers/profiles.py`).
- Per-RPC `profile` param scopes `HERMES_HOME` per call (`server.py:1358-1508`).

### Reconnect
- No event replay; orphaned sessions get a ~20 s grace before reap
  (`server.py:167-180,1107`); `session.reclaimed` broadcast on reap.
- Recovery = re-`session.resume` + REST transcript refetch + `inflight` merge.
- Desktop backoff reference: full-jitter, base 300 ms, cap 15 s
  (`apps/desktop/src/lib/reconnect-backoff.ts`).

## API-server surface (default port 8642)

Canonical source: `gateway/platforms/api_server.py`. `ApiServerGateway`
implements it as the app's primary connection path:

- **Auth** (`_check_auth`, api_server.py:1781-1830): `Authorization: Bearer
  <API_SERVER_KEY>` on every request — the token is trimmed client-side, the
  server strips + `hmac.compare_digest`s it and answers 401
  `gateway_auth_failed`. Nothing else is used: no `X-API-Key`, no
  `/api/status`, no cookies, no dashboard-password flow, and `/api/sessions`
  is never used for validation.
- **Validation + discovery**: `GET /v1/capabilities` (auth-required;
  api_server.py:3094). Its `features`/`endpoints` payload gates everything
  optional at runtime — e.g. `session_resources` enables
  `GET /api/sessions`, `GET /api/sessions/{id}/messages`,
  `DELETE /api/sessions/{id}` (404 = already gone), and `session_update`
  enables rename via `PATCH /api/sessions/{id}`. Deployments without those
  endpoints fall back to a local view of app-opened sessions.
- **Chat**: `POST /v1/chat/completions` `{model, messages, stream:true}` with
  `X-Hermes-Session-Id` session continuity (api_server.py:4168-4175); SSE
  deltas at `choices[0].delta.content`, tool activity as
  `event: hermes.tool.progress` frames, `data: [DONE]` terminator; dropping
  the connection is the interrupt signal. New sessions use client-generated
  durable ids (`mob-<ts>-<uuid>`).
- **Models**: `GET /v1/models` → `{data:[{id}]}`.
- **Secrets**: the token is Keystore-encrypted at rest, no log statements
  exist in app/protocol code, and every user-facing error string passes
  through `redact()` (strips the token value, `token=`/`ticket=`/`key=`
  query params, and Bearer header values).

- **Session-bound streaming — the primary turn path** (used when
  `features.session_chat_streaming` is advertised): the SERVER session is the
  source of truth. New conversations are created with
  `POST /api/sessions {source:"android"}` (the server mints the canonical id,
  api_server.py:~3662-); turns run via
  `POST /api/sessions/{id}/chat/stream {message, model?, model_options?}` —
  the server loads `_conversation_history_for_session(session_id)` itself and
  persists the turn, so the client never resends the transcript. SSE events
  (named `event:` frames): `run.started{run_id}` (pollable/stoppable via
  `/v1/runs/{run_id}`), `assistant.delta{delta}`,
  `tool.progress{tool_name,delta}` (`_thinking` → reasoning),
  `tool.started/completed/failed{tool_name,preview}`, `run.completed`,
  `error{message}`, `done`. Client disconnect interrupts the live run. A 404
  on the session row (pre-upgrade client ids) falls back to a runs turn with
  explicit history inside the same send.

- **Structured runs** (fallback when session chat streaming is absent but
  `features.run_submission` is advertised):
  `POST /v1/runs` `{input, session_id, model?}` → 202 `{run_id}`
  (api_server.py:6563-6992); `GET /v1/runs/{run_id}/events` SSE with data-only
  frames carrying `event` ∈ `message.delta{delta}`, `tool.started{tool,preview}`,
  `tool.completed{tool,duration,error}`, `reasoning.available{text}`,
  `subagent.start|complete{goal,model,summary,…}`,
  `approval.request{command,choices,…}`, `approval.responded{choice}`,
  `run.completed{output,usage}`, `run.failed{error}`, `run.cancelled`;
  30 s `: keepalive` comments. Approvals resolve via
  `POST /v1/runs/{id}/approval` `{choice: once|session|always|deny}`
  (api_server.py:7045), stop via `POST /v1/runs/{id}/stop`. If the stream
  drops without a terminal frame the client settles from the pollable
  `GET /v1/runs/{id}` status instead of guessing. Runs take text-only input,
  so image-bearing turns ride chat completions (image parts as
  `data:image/…;base64` URLs, api_server.py:544-640).

Not yet used from this surface (available upstream, candidates for next
milestones): `/v1/responses`, `/v1/runs/{id}/steer`, `/v1/skills`,
`/v1/toolsets`, `/api/jobs` (cron), `/api/model/options`, session
fork/model-lock, and `/p/{profile}/…` profile mirrors
(api_server.py:2050-2103, :7363).

## Adapted code / patterns

From **luinbytes/hermes-android** (MIT — see `THIRD_PARTY_NOTICES.md`):
- JSON-RPC frame DTO shapes (`protocol/JsonRpcModels.kt`) → our `wire/JsonRpc.kt`.
- Request/response correlation + connection-generation guard
  (`protocol/OkHttpHermesGatewayClient.kt`) → our `wire/GatewaySocket.kt`
  (rewritten with transport-owned indefinite reconnect).
- Dashboard cookie-name matching incl. `__Host-`/`__Secure-` prefixes
  (`network/DashboardAuthClient.kt`) → our `wire/HermesRestClient.kt`.
- Keystore AES/GCM token storage pattern (`security/SecureTokenStore.kt`)
  → our `security/CryptoBox.kt`.

From **rusty4444/hermes-android** (behavioral spec only, no code):
- Never auto-resubmit an ambiguous `prompt.submit`.
- Wait for terminal events, not RPC acks.
- Session id may arrive as `sid` or `session_id` on events.
- `message.interim already_streamed` seal semantics.

## Compatibility notes / known upstream gaps

- No stable ids for *live-streamed* messages until persisted → local
  generation counters + wholesale rehydrate (upstream: no event replay).
- Live event stream binds to the submitting client; other clients poll
  authoritative history while `running` (documented in ARCHITECTURE.md §3).
- No push-notification transport upstream; M4 plans a minimal VPS-side
  webhook → UnifiedPush relay.
- Token mode is unusable from LAN against loopback binds (peer check);
  remote setups need gated mode or a tunnel that presents a loopback peer.

## Re-audit procedure

1. `scripts/audit-upstream.sh` — clones/updates the three upstreams and diffs
   the protocol-bearing files against the refs pinned above.
2. Check `DESKTOP_BACKEND_CONTRACT` (`tui_gateway/server.py`) — a bump means
   new capabilities to gate on `session.info.desktop_contract`.
3. Re-run `./gradlew :protocol:test`.
4. Update the pinned refs + notes here in the same PR as any `:protocol` change.
Do not merge community-client UI changes into this app.
