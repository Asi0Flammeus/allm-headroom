# ALLM Headroom

# ALLM Headroom is a private AI capacity monitor for Android. Its home-screen
panel shows remaining Claude (Anthropic) and Codex (OpenAI) subscription
headroom, reset horizons, and a live pace line. Tapping the panel opens the
Headroom app, with current capacity and locally retained capacity history.

It is fed by `agent-sub-usage` on crqpt-server through a read-only authenticated
tailnet bridge. No provider credentials,
OAuth tokens, or server secrets ever reach the phone: the APK ships with no
baked-in secrets, and the bridge response carries only percentages and reset
times.

```
agent-sub-usage (server, OAuth creds stay here)
  └─ sub-usage-bridge  127.0.0.1:8378   ~/.ghost/services/sub-usage-bridge/server.py
       └─ tailscale serve (WireGuard, tailnet-only, real TLS cert)
            └─ https://crqpt-server.tail58339.ts.net:8377/usage  + Bearer token
                 └─ widget (WorkManager, 15-min refresh + tap-to-refresh)
```

## Security boundary

- **Transport**: `tailscale serve` — listener exists only on the tailnet
  (WireGuard); a valid Let's Encrypt cert for `crqpt-server.tail58339.ts.net`
  terminates TLS. Nothing is exposed on public interfaces; the bridge itself
  binds `127.0.0.1:8378`.
- **AuthN**: static bearer token (`~/.ghost/services/sub-usage-bridge/token`,
  0600), compared constant-time. On the phone it lives in app-private
  SharedPreferences and grants read-only usage percentages only.
- **Read-only**: GET only; the bridge subprocess-calls `agent-sub-usage` and
  never mutates anything. Response fields: per provider/window
  `remaining_percent`, `used_percent`, `resets_at`, `seconds_until_reset`,
  `available` + freshness metadata. Nothing else.
- `/apk` on the same port serves the APK itself (no secrets inside) so any
  tailnet device can download and install it.

## Freshness semantics (both sides)

Server (`/usage`): `fetched_at`/`age_seconds`/`stale` (age > 45 min) and
`fetch_error` when the newest probe failed but older data is served; HTTP 503
when there has never been data. Probe results are cached 5 min (`SUB_BRIDGE_TTL`)
and persist across restarts (`cache.json`).

Widget precedence: UNCONFIGURED → NO_DATA → **ERROR** (red dot, `offline · data
from HH:MM`, last fetch attempt failed, cached values stay visible) → **STALE**
(amber dot, `stale · data from HH:MM`, server flagged stale or our own last
success is >45 min old) → **FRESH** (green dot, `updated HH:MM`).

Rows carry the provider logomark (Anthropic terracotta / OpenAI white, bundled
vectors) on each group's first line. Every bridge window renders in bridge
order: available ones as a progress bar colored by remaining headroom (green
>30 %, amber 11–30 %, red ≤10 % — same bands as the percent text), unavailable
ones as explicit `no data` rows that keep their window label (the OpenAI probe
goes down intermittently; its 5h/week rows stay visible).

## Refresh cadence

WorkManager periodic fetch every **15 minutes** (network-constrained), plus
`updatePeriodMillis=30 min` as system backstop, plus **tap the widget** for an
immediate refresh. Gear icon opens pairing.

## Install on the Fold 8

1. Tailscale app: connected (device `williams-z-fold8` on the tailnet).
2. Get the APK, either way:
   - Tailscale app → Files: accept `fold-usage-widget-0.1.0.apk` (Taildrop),
   - or open `https://crqpt-server.tail58339.ts.net:8377/apk` in the browser.
3. Open the downloaded file, allow install-unknown-apps for the browser/files
   app when prompted (sideload; signed with the project key in `keystore/`).
4. Open **ALLM Headroom**, paste the private relay URL (prefilled) and the token:
   `cat ~/.ghost/services/sub-usage-bridge/token` on crqpt-server.
5. **Test** → expect `OK ✓ N usage windows` → **Save** → **Add panel to home
   screen** (or long-press home screen → Widgets → AI headroom).

## Product surfaces

- **Headroom panel** — a glanceable home-screen capacity instrument. Its pace
  line is computed locally from reset time and repaints every minute on a
  best-effort basis, independent of the 15-minute private-relay fetch.
- **Capacity view** — tap the panel to open the app. It shows current provider
  windows and a 30-day, device-local capacity history.
- **Threshold gradient** — capacity is calm above 30%, then moves from amber
  through orange to deeper red at 10%, 5%, and below. This is deliberately a
  gradient of urgency rather than a single abrupt warning state.
- **Model mix (planned data source)** — subscription APIs currently expose
  aggregate usage windows, not model-attributed token events. The app says so
  rather than inventing token counts. When the private relay can supply these
  events, the intended view is daily stacked bars: Anthropic orange, OpenAI
  green, with deeper tones for higher-tier models.

## Server pieces

- `~/.ghost/services/sub-usage-bridge/server.py` — stdlib-only bridge
  (env-tunable: `SUB_BRIDGE_PORT/HOST/TTL/STALE_AFTER/TOKEN_FILE/CACHE_FILE`,
  `SUB_USAGE_CMD`); full schema in its docstring.
- `~/.config/systemd/user/sub-usage-bridge.service` — enabled, running.
- Tailnet exposure (idempotent, survives reboots as tailscaled state):
  `sudo tailscale serve --bg --https=8377 http://127.0.0.1:8378`
  `sudo tailscale serve --bg --https=8377 --set-path=/apk /data/crqpt/repos/Asi0Flammeus/fold-usage-widget/fold-usage-widget-0.1.0.apk`

## Build & test (server-side)

```
cd /data/crqpt/repos/Asi0Flammeus/fold-usage-widget
./gradlew :app:assembleRelease :app:testReleaseUnitTest   # APK + display-state tests
```
SDK at `/home/crqpt/android-sdk` (local.properties), AGP 8.13.2, Kotlin 2.2.21,
gradle 8.14.5 wrapper — same toolchain as fold8-pdf-reader. Release signing
uses `keystore/fold-usage-widget.jks` + `keystore/keystore.properties`
(gitignored, 0600; keep them or future updates need an uninstall/reinstall).
Output: `app/build/outputs/apk/release/app-release.apk`, stable copy
`fold-usage-widget-0.1.0.apk` at the project root.

Smoke-tested 2026-08-19 on the `fold8test` AVD (android-36): paired through the
real UI, pinned the widget, and drove all three states — fresh (green, live
percentages), stale (amber, values retained), offline (red, values retained) —
by pointing the widget at a stub bridge in matching modes. Bridge fresh/stale/
error/401 semantics verified with curl against real, cached-failing, and
never-succeeded instances.

versionCode 2 (2026-08-19, same 0.1.0 filename): provider logomarks, per-window
no-data rows, headroom-banded bar colors. Verified with the JVM display-state
suite (20 tests, incl. band boundaries) and an `aapt2 xmltree` check of the
compiled row layout; state-machine behavior above is unchanged.

versionCode 3 (2026-08-19, same 0.1.0 filename): provider marks now vertically
centered across a provider's whole row block (not just the group's first
line); the sub-usage-bridge systemd unit's PATH was missing `~/.bun/bin`,
which silently broke OpenAI's Codex probe — fixed and confirmed live; and each
available window now carries a linear-pace tick (`<scale>` + `<layer-list>`
drawable, driven by `RemoteViews.setInt(id, "setImageLevel", …)`, no pixel
math or API-level dependency) showing where remaining% would sit right now
under asi0's own straight-line rule (`100 * secondsUntilReset /
windowLengthSeconds`; 5h and 7-day window lengths, keyed by the bridge's
stable window id). 25 JVM tests (5 new, incl. pace boundaries and the
weekly/weekly-scoped duration mapping).
