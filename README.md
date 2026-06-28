# OSRS Algo — Grand Exchange auto-log

Auto-logs every Grand Exchange offer (place, partial fill, full fill, cancel) to a
user-configured local backend over HTTP — so personal trading dashboards / spreadsheets
can ingest GE activity automatically instead of via manual entry.

## Features

- Per-slot lifecycle UUID dedup — the same offer never lands twice even if Runelite re-fires the event
- Retry + offline JSONL queue — if the backend is down, events are buffered locally and drained on reconnect
- Startup reconcile — restart Runelite mid-trade and the plugin re-binds to existing open orders via the natural key (item + side + qty + price)
- Minimal status panel — green dot when healthy, amber when events are queued, red when backend is unreachable
- No GE tax math, no manual price entry — the plugin captures `spent / quantitySold` directly from the GE offer

## Configuration

Open the plugin's config in Runelite (`OSRS Algo (GE log)`):

- **Backend URL** — Base URL of your local backend. Default: `http://127.0.0.1:5000`
- **Send events** — Toggle off to observe GE events without posting (debug mode)

## Privacy

This plugin sends Grand Exchange offer state to a URL of your choosing — by default
`http://127.0.0.1:5000` (localhost only). No data leaves your machine unless you set
the URL to a remote backend.

## Wire contract

For developers wiring this up to a custom backend: the plugin POSTs JSON to
`<BackendURL>/api/ge-events` on every offer state change. On startup it POSTs to
`<BackendURL>/api/ge-events/reconcile` to rebind existing open orders.

POST `/api/ge-events` body:

```json
{
  "plugin_lifecycle_id": "uuid-per-slot-instance",
  "slot_index": 0,
  "item_id": 11212,
  "item_name": "Dragonstone bolts (e)",
  "side": "buy",
  "status": "pending|partial|filled|cancelled",
  "total_quantity": 11000,
  "filled_quantity": 5000,
  "price": 379,
  "avg_fill_price": 378.5,
  "spent": 1892500,
  "event_ts": "2026-06-27T15:00:00Z"
}
```

Expected response: `{"order_id": <int>, "created": <bool>}`.

The same `plugin_lifecycle_id` is sent on every state change for a given GE slot
occupancy, so the backend can upsert (create on first sighting, update on subsequent).
The plugin caches the returned `order_id` to skip redundant lookups.

POST `/api/ge-events/reconcile` body (on plugin startup):

```json
{
  "slots": [
    {"slot_index": 0, "item_id": 11212, "side": "buy",
     "total_quantity": 11000, "price": 379, "filled_quantity": 5000}
  ]
}
```

Expected response: `{"matches": {"0": {"order_id": 42, "plugin_lifecycle_id": "..."}, ...}}`
— omit unmatched slots from the map.

## Source

<https://github.com/RichardLechko/osrs-algo-ge-log>
