# Bot Mode — design source of truth

Mobile Bot Mode follows the **Hermes Desktop Bot Mode** plugin. Public
upstream: https://github.com/NousResearch/Hermes-Bot-Mode (plugin.js,
tests, docs/bots-pane.png). The installed ALARA plugin is AHEAD of
upstream; this doc keeps the four layers separate.

## 1. Public upstream behaviour (verified from source)

- One canonical conversation per bot, `session.create {profile,
  title: "Bot Chat"}`, optionally born `hidden` from the global sidebar.
- Roster row: avatar + bot name + latest-message preview + timestamp.
- "Active now" presence: the gateway-busy profile plus any bot that wrote
  within the last 90 seconds (`ACTIVE_WINDOW_S = 90`); never reorders
  the roster.
- Avatars: flat geometric body + two eyes; default shape =
  `hash(name) % [circle, squircle, pill, triangle, hexagon, cloud, drop]`
  (`hash = hash*31 + charCode`, uint32); default body `#f97316`; eyes
  flip light on dark bodies (luminance < 110). Customization stored in
  desktop plugin storage upstream.
- Bot-to-bot messages: `Message from 🤖 <name> (@<name>): …` prefix;
  @mentions hand off and report back.
- New Agent / Edit / Duplicate / Delete, Groups, Routines panes.

## 2. Installed ALARA behaviour (ahead of upstream)

- Persistent Group/room support.
- Quiet manager/specialist handoffs: one frontstage manager voice, one
  eyes/working acknowledgement, specialists backstage, no repeated
  self-introductions.
- Syncs profile UI metadata and avatar assets through the BACKEND (so
  custom avatars are not desktop-only).
- Special-cases the primary/default bot's appearance.
- Live VPS roster (not to be hardcoded anywhere): default→Sol,
  alex→Alex · Production, deepseek→DeepSeek Flash, kimimal→Kimi,
  malfable→Fable, malgrok→Grok, moa→Opus Council,
  solseoyeon→Sol Seoyeon. Display identities come from the server, not
  the app.

## 3. Implemented mobile behaviour

- Roster discovered from authenticated `GET /v1/profiles`; each
  profile's `api_prefix` is authoritative for every request. Backwards
  compatible with the minimal `{name, api_prefix}` response.
- Optional enriched fields are consumed when the server provides them
  (Codex is adding them server-side): `display_name`,
  `description`/`role`/`title`, `model`, avatar metadata (`avatar.shape`,
  `avatar.color`, `avatar.image_url` or flat `avatar_*` keys), and roster
  summary (`preview`/`last_message_preview`, `last_active`,
  `busy`/`running`) — so the app does NOT fan out eight per-profile
  session queries.
- Avatar precedence: server image (auth header only to the gateway host)
  → server shape/colour → upstream geometric default.
- Per-bot presence dot: busy, or last activity within 90s; hidden
  entirely when the server sent no summary fields (no fake presence).
  The header dot is the overall gateway connection — a separate signal.
- Row: avatar · display name · latest preview (fallback role/model) ·
  relative timestamp · selected state.
- One canonical "Bot Chat" per profile (found by title, created on
  demand, titled on first message). Separate histories and drafts per
  profile.
- Full chat capabilities in Bot Chats: runs streaming, tool activity
  rows (collapsible), stop, steer, approval cards, authenticated MEDIA:
  video streaming. Works with the Mac off — the VPS is the runtime.
- No profile creation/deletion/editing from mobile.

## 4. Pending mobile behaviour (honest gaps)

- **Creator rooms — client implemented, NOT yet operational.** v0.8.0
  ships the full client side against docs/BOT_ROOMS_API.md: Rooms tab,
  server-discovered rooms, one persistent transcript, structured
  member-only @mentions with idempotency ids, manager-only frontstage
  with specialists collapsed into activity rows, media via the existing
  MEDIA pipeline, stop/steer/approvals on the shared runs engine, and a
  quiet "Rooms require a newer ALARA server" state. It stays dormant
  until Codex deploys the VPS endpoints and advertises
  `features.bot_mode_rooms`; rooms must NOT be claimed operational until
  an end-to-end test against the live VPS passes.
- Group sections in the BOTS roster (ALARA groups) — pending a server
  contract for group membership.
- "Active now" strip above the roster — pending; per-row presence dots
  cover the need meanwhile.
- Pet companions / blinking-eye animation — cosmetic, pending.
- Desktop screenshots of the installed ALARA plugin are still wanted in
  `docs/design-reference/` to verify visual details (spacing, selected
  state, working acknowledgement styling).
