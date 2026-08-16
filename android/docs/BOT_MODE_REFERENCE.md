# Bot Mode — design source of truth (status: awaiting desktop reference)

The mobile Bot Mode must follow the **Hermes Desktop Bot Mode** Alan uses
(hermes-bots desktop plugin), not a generic bot roster.

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
