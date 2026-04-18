# fonephish

Two Android apps that turn one phone into a remote-controllable screen showing
a specific web page:

- **host** — locks itself to a configured URL inside a fullscreen WebView,
  hides the status / nav bars, captures the screen with `MediaProjection`, and
  runs a WebSocket server that streams JPEG frames and receives input events.
- **controller** — connects to the host over WebSocket, draws each frame to a
  `SurfaceView`, and forwards touches, typed text, Backspace, and Enter back
  to the host so the page behaves as if it were local.

The host phone is the server. Expose it publicly with a Cloudflare Tunnel (see
below) so the controller can reach it via a domain over `wss://`.

## Build

Requires Android Studio / Gradle 8.5+, JDK 17, Android SDK 34.

```bash
./gradlew :host:assembleDebug
./gradlew :controller:assembleDebug
```

APKs land in `host/build/outputs/apk/debug/` and
`controller/build/outputs/apk/debug/`.

## Running the host

1. Install `host` on the phone that will display the page.
2. Launch it, enter the URL (e.g. `https://example.com/login`) and a port
   (default `8080`), tap **Start Sharing**, and accept the screen-capture
   prompt.
3. The kiosk activity opens, loads the page full-screen, and starts a
   foreground service running the WebSocket server on the chosen port.
4. When Android asks to pin the app, allow it. Pinning is what prevents the
   user from swiping to Home / Recents. Back navigates inside the WebView only.

## Running the controller

1. Install `controller` on the phone that will remotely drive the page.
2. Launch it, enter the host endpoint. Supported forms:
   - `wss://your-tunnel.example.com`
   - `ws://192.168.1.42:8080` (same LAN)
3. Tap **Connect**. The page appears; touches and drags go straight through
   to the host's WebView. Tap any input on the page, then tap the text field
   at the bottom of the controller screen and type — characters are mirrored
   to the host in real time (including Backspace and Enter / Done).

## Exposing the host via Cloudflare Tunnel

The host's WebSocket server binds to `0.0.0.0:<port>` on the device. To reach
it from anywhere behind a domain, run `cloudflared` on the host phone (e.g.
via Termux) or on a small helper box on the same LAN:

```bash
# In Termux on the host phone (or a LAN box that can reach it):
pkg install cloudflared            # Termux
cloudflared tunnel --url http://localhost:8080
```

Cloudflare prints a `https://<random>.trycloudflare.com` URL. Use the same
hostname with `wss://` in the controller (the `ConnectActivity` auto-upgrades
`https://` to `wss://`). For a stable custom domain, create a named tunnel:

```bash
cloudflared tunnel login
cloudflared tunnel create fonephish
cloudflared tunnel route dns fonephish phone.yourdomain.com
cloudflared tunnel run --url http://localhost:8080 fonephish
```

Then point the controller at `wss://phone.yourdomain.com`. Cloudflare
transparently upgrades the WebSocket; no extra config needed for `ws`-over-
`https` as long as the tunnel targets the plain HTTP port the host's
WebSocket server is listening on.

## Wire protocol

- Host → controller (binary): `[u16 BE width][u16 BE height][u32 reserved][JPEG bytes]`
- Controller → host (text JSON):
  - `{"t":"touch","a":"down|move|up|cancel","x":0..1,"y":0..1}`
  - `{"t":"text","v":"hello"}`
  - `{"t":"key","k":"Backspace"}` or `"Enter"`
  - `{"t":"scroll","dy":<pixels>,"x":0..1,"y":0..1}`

Coordinates are normalized so the two phones don't need to agree on
resolution. The controller's `SurfaceView` letterboxes the host frame, so
taps outside the letterboxed content area are clamped.

## Caveats

- Screen pinning (lock task) needs user approval on first launch. For
  tamper-proof kiosk behavior, provision the host as a Device Owner with
  `dpm set-device-owner com.fonephish.host/.Admin` — not included here.
- Input injection targets the host's own WebView via
  `WebView.dispatchTouchEvent` and DOM-level JS for text. System-level
  keyboards, IME candidates, and native Android UI outside the WebView are
  not controllable.
- Frames are JPEG at ~15 fps and 720px long-side to stay well under typical
  tunnel bandwidth. Tune in `ScreenCaptureService`.
