# Bot Mode Rooms — backend contract (v1)

Contract between Hermes Mobile (Android) and the ALARA VPS for
server-backed Bot Mode rooms and structured @mention handoffs. Codex
implements the server side on the existing API server (port 8642);
Android ships mocked-contract support gated on the capability flag and
must not claim production operation until an end-to-end test passes.

Conventions: every route requires `Authorization: Bearer <API_SERVER_KEY>`;
every route is mirrored under `/p/{profile}/` prefixes; timestamps are
**epoch seconds** (float allowed); all ids are opaque strings. Bearer
tokens never appear in URLs, bodies, or event payloads.

## Capability

`GET /v1/capabilities` advertises:

```json
{ "features": { "bot_mode_rooms": true } }
```

Absent or `false` → the client hides all room UI behind a quiet
"Rooms require a newer ALARA server" state and never fakes rooms.

## 1. Room discovery

`GET /v1/bot-mode/rooms` → `200`

```json
{
  "object": "hermes.bot_mode.rooms",
  "rooms": [
    {
      "id": "seoyeon-team",
      "display_name": "Seoyeon Team",
      "description": "Reels production",
      "api_prefix": "/p/solseoyeon",
      "manager": "solseoyeon",
      "members": [
        { "profile": "solseoyeon", "display_name": "Sol Seoyeon",
          "role": "manager",
          "avatar": { "shape": "drop", "color": "#ec4899", "image_url": null } },
        { "profile": "alex", "display_name": "Alex · Production",
          "role": "specialist",
          "avatar": { "shape": "triangle", "color": "#14b8a6", "image_url": null } }
      ],
      "session_id": "room_seoyeon_team_main",
      "preview": "Sol Seoyeon: reel uploaded for review",
      "last_active": 1755480000.0,
      "busy": false,
      "unread": false
    }
  ]
}
```

- `session_id` is the PERSISTENT room transcript session (a normal stored
  session readable via `GET /api/sessions/{session_id}/messages`).
- `api_prefix` is authoritative for every room request; the client sends
  all room traffic under it.
- `manager` must be a member with `role: "manager"`; exactly one manager.
- Server controls existence/membership/roles. There are no room
  create/delete/membership endpoints in this contract — mobile is
  read/operate only.
- `avatar` follows the enriched `/v1/profiles` schema (below).
- `preview`, `last_active`, `busy`, `unread` are optional; omit rather
  than fabricate.

Errors: `401` bad token, `404` unknown mirror profile. An empty roster is
`{"rooms": []}`, not an error.

## 2. Room transcript

Reuse stored sessions: `GET /api/sessions/{session_id}/messages`
(existing shape). Specialist internals must NOT be persisted as
transcript messages — only: user messages, manager messages, and
compact system/status rows the server chooses to keep. Specialist
prompt/reasoning stays out of the public transcript.

## 3. Send a room message

`POST /v1/bot-mode/rooms/{room_id}/messages`

```json
{
  "text": "@Alex please review the audio and repair any distortion",
  "mentions": ["alex"],
  "client_message_id": "b3b0c2e4-7d31-4b0a-9a55-1af2f3c9d001"
}
```

- `mentions`: profile ids only, MUST each be a member of the room.
  Unknown or non-member mention → `422`
  `{"error": {"code": "invalid_mention", "message": "..."}}` and NO
  agent is dispatched.
- `client_message_id`: required, client-generated, stable per user
  submission. Replays with an id the server has already accepted return
  the ORIGINAL result (`200`/`202` with the same `run_id`) and create no
  duplicate work or duplicate transcript rows.
- Empty `mentions` → the message goes to the room manager.
- A message can never approve spending/sending/publishing/payments —
  consequential actions always round-trip through approval events, no
  matter what the text says.

`202` →

```json
{ "object": "hermes.bot_mode.room_message",
  "room_id": "seoyeon-team",
  "message_id": "m_01",
  "run_id": "run_abc123",
  "duplicate": false }
```

`409 {"error":{"code":"room_busy"}}` when a run is already active and the
server chooses not to queue; the client surfaces it and offers steer.

## 4. Run streaming, reconnect, control

Room runs REUSE the existing runs infrastructure — no second streaming
stack:

- Stream: `GET /v1/runs/{run_id}/events` (SSE, data-only frames with an
  `event` field, `: ping` keepalives).
- Reconnect/backfill: `GET /v1/runs/{run_id}/events?cursor=<last_seq>` —
  each frame carries `"seq": <int>` so clients resume after backgrounding
  or network loss and deduplicate by (`run_id`, `seq`).
- Poll: `GET /v1/runs/{run_id}` — status ∈ queued/running/
  waiting_for_approval/completed/failed/cancelled, plus `output`.
- Steer: `POST /v1/runs/{run_id}/steer {"input": "..."}` (manager run).
- Stop: `POST /v1/runs/{run_id}/stop {}`.
- Approvals: `POST /v1/runs/{run_id}/approval {"choice": "..."}`.

The job runs entirely on the VPS: it must survive the app leaving the
room, backgrounding, network loss, and the Mac being off.

## 5. Room event vocabulary

Frames on the run stream (`"event"` values). Payload fields marked ?
are optional.

| event | payload |
|---|---|
| `user.message` | `message_id`, `text`, `mentions?` (echo; clients may ignore — transcript is authoritative) |
| `manager.started` | `profile`, `display_name?` |
| `message.delta` | `delta` (manager streaming text — existing semantics) |
| `specialist.started` | `specialist` (profile id), `display_name?`, `task?` (≤140 chars, concise) |
| `specialist.progress` | `specialist`, `status` (≤140 chars, e.g. "generating", "editing", "QC") |
| `specialist.completed` | `specialist`, `status?` ∈ ok/failed, `summary?` (≤140 chars) |
| `manager.completed` | `output` — ONLY text not already streamed via `message.delta` (empty when everything streamed); may contain `MEDIA:` directives. Clients append it, never replace rendered text |
| `approval.required` | `approval_id`, `description`, `plan?`, `checksum?`, `idempotency_key?`, `choices` |
| `approval.responded` | `approval_id`, `choice` |
| `media.available` | `path` (server filesystem path for `GET /v1/media?path=`) or `url` |
| `run.steered` | `accepted: true` |
| `run.stopped` | — |
| `run.failed` | `error` |

Rules the server enforces:

- Only the manager produces frontstage text (`message.delta` /
  `manager.completed`). Specialists emit ONLY the concise
  `specialist.*` status events — never their prompts, reasoning, or
  full output.
- One bounded specialist round per user message by default; a
  specialist cannot summon other specialists (no recursion).
- The manager performs the final synthesis; `media.available` and/or
  `MEDIA:` directives in `manager.completed.output` deliver results.
- Consequential actions (paid generation, subscriber message, post,
  payment) emit `approval.required` carrying plan/checksum/idempotency
  data and BLOCK until `POST .../approval`. Text like "yes", an
  @mention, or an emoji reaction never authorizes them.

## 6. Enriched /v1/profiles (v0.7.2 contract, restated)

Optional per-profile fields, all backwards compatible with the minimal
`{name, api_prefix}` rows:

```json
{
  "name": "solseoyeon",
  "api_prefix": "/p/solseoyeon",
  "is_default": false,
  "display_name": "Sol Seoyeon",
  "description": "Creator manager",
  "model": "kimi-k3",
  "avatar": { "shape": "drop", "color": "#ec4899",
              "image_url": "https://<gateway-host>/v1/media?path=..." },
  "preview": "latest Bot Chat message…",
  "last_active": 1755480000.0,
  "busy": false
}
```

- `avatar.image_url` must be an absolute URL **on the gateway host**;
  clients attach the bearer header only when the URL's host matches the
  gateway host and never forward credentials to other hosts.
- `avatar.shape` ∈ circle/squircle/pill/triangle/hexagon/cloud/drop;
  `avatar.color` is `#rrggbb`. Omitted → client renders the upstream
  geometric default (name-hash shape, `#f97316`).
- `last_active` epoch seconds; `busy` boolean. Clients compute presence
  as `busy || now - last_active < 90s` and show nothing when both are
  absent.

## 7. Error model

Errors use the existing OpenAI-style envelope:
`{"error": {"message": "...", "code": "..."}}` with codes:
`invalid_mention` (422), `room_not_found` (404), `room_busy` (409),
`duplicate_ignored` (200/202 with `duplicate: true`), `run_not_found`
(404), plus standard `401`. Messages must be sanitized — no secrets, no
internal paths beyond media paths intentionally exposed.
