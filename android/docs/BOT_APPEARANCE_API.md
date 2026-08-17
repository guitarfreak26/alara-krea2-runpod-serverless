# Bot Appearance — backend contract (v1)

Contract for editable, backend-synced bot avatars so mobile and desktop
always agree on how an agent looks. Reading already works (the enriched
`/v1/profiles` `avatar` object, see BOT_ROOMS_API.md §6); this adds the
WRITE side. Same conventions as the rooms contract: Bearer auth on every
route, `/p/{profile}/` mirrors, epoch seconds, no credentials in URLs.

## Capability

```json
{ "features": { "bot_appearance": true } }
```

Absent/false → mobile hides all appearance editing (no broken controls).

## Storage semantics

Appearance is PROFILE metadata held by the backend — the same store the
ALARA desktop plugin syncs through — so a change made on the phone shows
on desktop and vice versa. Precedence when rendering (both surfaces):
`image_url` → `shape`+`color` → client geometric default.

## 1. Set shape/colour

`PATCH /v1/profiles/{profile}/appearance`

```json
{ "shape": "hexagon", "color": "#8b5cf6", "clear_image": false }
```

- All fields optional; omitted fields keep their stored value.
- `shape` ∈ `circle|squircle|pill|triangle|hexagon|cloud|drop` →
  `422 {"error":{"code":"invalid_shape"}}` otherwise.
- `color` is `#rrggbb` → `422 invalid_color` otherwise.
- `clear_image: true` removes a stored image so shape/colour show again.
- The `{profile}` path segment is authoritative (clients call the root
  listener); unknown profile → `404 profile_not_found`.
- This route edits PRESENTATION only. It must not touch model, skills,
  SOUL.md, credentials, or any other profile configuration.

`200` → the updated avatar object, same shape as `/v1/profiles`:

```json
{ "object": "hermes.profile.appearance",
  "profile": "kimimal",
  "avatar": { "shape": "hexagon", "color": "#8b5cf6", "image_url": null } }
```

## 2. Upload an image avatar

`POST /v1/profiles/{profile}/appearance/image`

```json
{ "image_base64": "<base64>", "mime_type": "image/jpeg" }
```

- Accept `image/jpeg`, `image/png`, `image/webp`.
- Cap decoded size at 2 MB → `413 image_too_large`. The server may
  downscale/re-encode (e.g. 512×512) before storing.
- `200` → same envelope as above with `avatar.image_url` set to an
  absolute URL **on the gateway host** (e.g. served through the existing
  `/v1/media` route or a dedicated avatar path). Clients attach the
  bearer header only when the URL host matches the gateway host.

## 3. Non-goals

- No avatar DELETE beyond `clear_image` (an upload replaces the old one;
  the server may garbage-collect replaced images).
- No profile create/delete/rename — presentation only.
- Pets/animations remain desktop plugin territory for now.

## Client behaviour (implemented, dormant until the capability)

- Long-press a bot in the roster → "Edit look" sheet: live preview,
  the 7 native shapes, the 10-colour upstream palette, photo upload,
  remove photo. Saves via the routes above, then re-reads `/v1/profiles`
  so the roster (and desktop, on its next read) shows the new look.
