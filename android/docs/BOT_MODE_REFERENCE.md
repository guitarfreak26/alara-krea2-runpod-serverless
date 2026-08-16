# Bot Mode — design source of truth

The mobile Bot Mode follows the **Hermes Desktop Bot Mode** plugin. The
public upstream is https://github.com/NousResearch/Hermes-Bot-Mode
(plugin.js + docs/bots-pane.png) and is the primary design reference.

## Contract confirmed from upstream source

- Canonical conversation: ONE per bot, created via `session.create
  {profile, title: "Bot Chat"}` (optionally born `hidden` from the global
  sidebar). Mobile finds it by title and creates it on demand.
- Roster row: avatar + bot name + latest-message preview + timestamp.
- "Active now" presence: gateway-busy profile plus any bot that wrote in
  the last 90 seconds (`ACTIVE_WINDOW_S = 90`); never reorders the roster.
- Avatars: flat geometric body + two eyes. Default shape =
  `hash(name) % [circle, squircle, pill, triangle, hexagon, cloud, drop]`
  with `hash = hash*31 + charCode (uint32)`; default body colour
  `#f97316`. Custom colours/images/pets live in desktop **plugin storage**
  — not on the server — so mobile renders default looks for customized
  bots. Mobile reimplements the shape+eyes system with the same hash.
- Bot-to-bot messages arrive prefixed `Message from 🤖 <name> (@<name>):`.
- @mentions hand off to another bot and report back (desktop-side CLI
  behaviour; mobile displays the transcript it produces).

## Reference material still needed

This cloud workspace cannot reach Alan's Mac. The following references are
**inaccessible from here** and must be supplied (screenshots or a short
recording) before final visual polish:

- Running Hermes Desktop in Bot Mode: full roster, selected/unselected rows,
  avatars + status indicators, Bot Chat conversation, active-bot header,
  switching between bots, working/eyes acknowledgements, agent settings,
  dark-mode colours/spacing/typography.
- Plugin source (read-only):
  `~/.hermes/desktop-plugins/hermes-bots/plugin.js`
- `~/.hermes/desktop-plugins/hermes-bots/ALARA_COMPATIBILITY.md`
- `~/clawd/fanvue-automation/docs/VPS_RUNTIME_OWNERSHIP.md`
- `~/clawd/fanvue-automation/docs/ALARA_CREATOR_PODS.md`

Add the supplied screenshots to `docs/design-reference/` (no credentials or
private runtime configuration in the images).

## Behavioural contract implemented now (from the written spec)

- Roster is discovered dynamically from authenticated `GET /v1/profiles`;
  each profile's `api_prefix` is authoritative for every subsequent request.
  Nothing hardcodes the current profiles.
- Each bot maps 1:1 to an existing VPS profile. The app never creates
  duplicate agents/profiles to display them.
- Every bot has one canonical conversation titled **"Bot Chat"**; switching
  bots preserves separate histories and drafts (drafts are keyed by session).
- Runs API (`/v1/runs*` under the profile prefix) provides streaming,
  stop, steer and tool-approval.
- Quiet multi-agent design: the selected manager is the main voice; tool /
  specialist activity is collapsed behind the existing tool-activity rows
  (toggleable in Settings) plus the single "working" status row — no
  specialist self-introductions, no recursive agent conversations, no faked
  shared rooms.
- No New Agent / Delete Agent / profile editing on mobile — the app
  operates the roster, it does not administer production profiles.
- Extension point: `BotsScreen`'s roster + `openBotChat(profileId)` are the
  seams for future creator rooms (e.g. Sol Seoyeon + optional reviewers).

## Mobile translation choices (pending desktop screenshots)

- Bot Mode is a drawer destination opening on the roster.
- Rows: identity-coloured avatar (initial), display name, role/model line,
  availability dot, obvious selected state.
- Tap → that bot's Bot Chat with the standard chat header (bot identity
  stays visible); back returns to the roster.
