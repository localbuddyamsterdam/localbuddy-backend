# Meetup Radar — design (build after personas are watertight)

Uber-style live meetup view for the minutes around an experience start: the host
sees a dot for each checked-in traveller of their slot, each traveller sees only
the host's dot. Goal: nobody stands lost at a canal corner.

## Product rules
- **Time-boxed**: sharing is possible only from T−30min to T+30min of a confirmed
  booking's slot (server-enforced, not just UI).
- **Opt-in per booking**: both sides tap "Share my location" — never ambient.
  A visible "sharing" indicator + one-tap stop.
- **Asymmetric visibility**: host ↔ their slot's travellers only. Travellers never
  see each other. Nothing is persisted beyond the session (in-memory / TTL cache,
  no location rows in Postgres — GDPR-friendly, nothing to export/delete).
- **Fallback**: when a party doesn't share, show the static meeting point + the
  existing WhatsApp click-to-chat link instead.

## Architecture (all €0 external cost)
- **Frontend**: browser Geolocation `watchPosition` (~5s throttle) → WebSocket.
  Dots rendered on the MapLibre map (host = accent/red marker, travellers = blue),
  with distance-to-meeting-point label. Stop on tab close / T+30 / manual stop.
- **Backend**: Spring WebSocket (STOMP) endpoint `/ws/meetup`; JWT handshake auth.
  Topic per slot: `/topic/meetup/{slotId}` with per-role filtering server-side
  (interceptor checks the subscriber is the slot's host or a confirmed traveller
  within the time window). Positions relayed, not stored (optionally Redis with
  60s TTL if we ever go multi-instance).
- **Scale/cost**: a slot has ≤10 participants; even hundreds of concurrent slots
  are trivial for one App Service instance. No Google/Mapbox fees — geolocation is
  a browser API, the map is MapLibre + free tiles. The only real costs are
  engineering time and privacy diligence (consent copy, indicator, auto-expiry).

## Later extensions
- Push notification "Your host is 100m away" (FCM, free) in the Flutter app.
- Geofenced auto check-in handshake with the existing attendance module (it
  already has a 300m geofence check).
