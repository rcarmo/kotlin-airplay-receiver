# Receiver Performance Notes

AirPlay Receiver targets Android TV and Google TV devices. Performance work is focused on predictable playback, fast return to the ready screen, and avoiding UI behavior that competes with media decode from the couch.

## Android TV Assumptions

The app keeps `minSdk` at 27 for broad Android TV coverage, while targeting API 34 for Play readiness in this phase.

The media path uses:

- `SurfaceView` for direct video composition.
- `MediaCodec` for H.264 decode.
- `AudioTrack` for PCM playback.
- JNI direct buffers for native RAOP, mirroring, AAC, crypto, and DNS-SD interop.
- A foreground service so Android treats the receiver as active while listening or playing.

The UI is remote-first. Remote volume keys control Android media volume, and the traffic monitor is toggled from the playback overlay. Onboarding, the default ready screen, and the active video overlay now use Compose as the first migration surfaces; playback remains a View-based `SurfaceView` path so media rendering is not disturbed by the UI migration.

## Ready Screen

The ready screen is intentionally simple:

- dark full-screen background
- receiver name
- `Ready to AirPlay`
- short sender instructions
- quality profile
- security mode
- D-pad actions for Settings, Quick Settings, and Help

It avoids network addresses, receiver IDs, and discovery internals. Diagnostics remain available through settings.

The current ready screen includes a clock with subtle pixel shifting. Reduce Motion disables movement. OLED dimming is implemented as UI-level dimming after a configurable idle interval so it does not depend on device-specific TV brightness APIs.

## Quality And Display

Quality profiles map onto the existing AirPlay `/info` resolution plumbing:

- Auto: choose 720p or 1080p based on display modes.
- Low Latency: 720p.
- Balanced: 1080p.
- Best Quality: 1080p.
- Compatibility: 720p.
- Audio Stable: 720p.

The video surface supports three fit modes:

- Fit: preserve aspect ratio with no crop.
- Fill: preserve aspect ratio and crop edges if needed.
- Stretch: fill the display even when aspect ratio changes.

Fit is the default because it avoids distortion. Fill is useful when a user wants all TV pixels covered. Stretch exists for compatibility with unusual sender/display combinations.

Frame-rate matching is exposed as a setting and uses `Surface.setFrameRate()` on API 30+. `RaopServer` estimates common sender cadences from mirroring timestamps and falls back to 60 fps when timestamps are missing, unstable, or outside the supported range.

## Hot Path

The hot path is unchanged in principle:

1. Native sockets receive encrypted audio/video data.
2. Native code decrypts and normalizes packets.
3. JNI copies packet payloads into native-owned direct buffers and passes `ByteBuffer` views to Kotlin.
4. Kotlin queues the direct buffer wrapper on the playback thread.
5. `VideoPlayer` copies video into `MediaCodec` input buffers.
6. `AudioPlayer` writes direct PCM buffers to `AudioTrack`.
7. Playback threads release native packet memory after write, decode, or drop.

The Kotlin side does not parse protocol state in the hot path. It hands packet boundaries to Android playback primitives.

## Latency Controls

Receiver prefers recovering to live playback over building delay:

- Video uses bounded queues sized for the known Android TV startup and decoder-restart path.
- Codec config is preserved for decoder restarts.
- Dependent video frames are kept in order before decode.
- Stale decoded output is drained so the newest waiting frame is rendered.
- Startup and render watchdogs only restart video decode when recent video packets are present; audio, feedback, or timing traffic alone is not treated as decoder progress.
- Audio uses a bounded queue, conservative prebuffer, and blocking writes.
- Queue pressure drops old audio packets instead of allowing unbounded delay.
- Playback threads use urgent display/audio priorities.
- Frame-level logging is disabled in normal builds.

The audio sync setting is applied in `AudioPlayer`. Positive offsets write initial silence before the first packet; negative offsets drop initial PCM. Changing the value flushes and re-primes playback so behavior remains deterministic. The Audio Stable quality profile increases Kotlin-side queue headroom, prebuffering, and the platform buffer multiplier for underrun-prone routes.

## Audio Route Awareness

The playback overlay reports the current likely output route using Android output device information:

- Bluetooth
- HDMI / ARC
- TV speakers
- system output fallback

The UI refreshes on route changes and warns on Bluetooth routes that audio sync may be needed. Route-specific persisted sync offsets remain future work because Android TV devices expose route identity inconsistently.

## Audio-Only Visualizer

The audio-only screen can show a simple bar visualizer driven by media byte samples. It is intentionally approximate, not a frequency analyzer:

- low-RAM devices use fewer bars
- samples are throttled
- animation pauses when hidden
- Reduce Motion keeps a quiet static display
- the visualizer can be disabled in settings

## Traffic Monitor

The traffic monitor remains a diagnostic overlay. It charts recent media throughput and receiver-side packet latency:

- Throughput is counted from decoded media bytes crossing into Kotlin.
- Latency starts when Kotlin receives a direct media buffer from JNI.
- Audio latency stops when `AudioTrack.write` is called.
- Video latency stops when `MediaCodec.releaseOutputBuffer(..., true)` hands the output buffer to the display surface.

These numbers measure receiver-side pressure, not sender capture latency or end-to-end AirPlay latency.

## Native Boundary

JNI still copies payloads because native RAOP/mirroring buffers are not owned by Android playback queues after callback return. The direct-buffer bridge reduces Java heap churn while keeping ownership explicit.

The build links shared native libraries with `-Wl,-z,max-page-size=16384` to prepare for Android devices using 16 KB memory pages. Release bundles include `arm64-v8a` for Play compliance and keep `armeabi-v7a` for Android TV devices that still expose a 32-bit userspace.

## Operational Guidance

For best results:

- Use a stable wired or strong Wi-Fi network.
- Keep the TV and sender on the same local network.
- Avoid guest Wi-Fi, VPNs, and router isolation when discovery is needed.
- Start with Auto quality.
- Use Low Latency or Compatibility if discovery works but playback is unstable.
- Use Audio Stable if audio underruns are frequent.
- Interpret traffic-monitor latency as local receiver pressure only.
- Prefer release builds for device testing.
- Test AAB output before Play submission.

Useful on-device checks are time to AirPlay picker visibility, first-frame time, reconnect behavior after disconnect, audio drift, Bluetooth route behavior, and whether repeated sessions leave native resources behind.
