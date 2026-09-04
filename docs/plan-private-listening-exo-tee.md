# Private Listening over an ExoPlayer audio-sink tee (NuvioTV)

> **Status: PROPOSAL — for senior-dev analysis (2026-09-04).** Not implemented, no code changes. Written to the same review loop as `docs/plan-search-people-episode-sets.md`: reviewer notes get folded in here and the design is then implemented as one or more feature branches off fork `dev`, compiled only on the build box (`192.168.68.63`, `:app:assembleFullRelease`).
>
> **Scope of this doc:** the TV-side Exo engine design. The hub control contract already exists (see §2.3); the phone-side receiver is a separate NuvioMobile (`companion-bridge`) follow-on, outlined in §8 only.
> File/line numbers are approximate and re-anchor at implementation, as usual.

## The product model

Roku Private Listening: the *playing device* is already decoding the audio that drives its own speakers; private listening **tees that decoded audio** and streams a copy over Wi‑Fi to the phone. It never re-opens the video, never runs a second decoder, and never needs the phone to fetch anything. A/V sync is free because the copy shares the main player's decode clock.

NuvioTV already has every prerequisite for this exact model (§2). The design is a tee wrapped around an audio sink the app *already* customizes, riding a message contract the hub *already* allow-lists, using a phone IP the hub *already* delivers to the TV.

## Why not the obvious alternatives (context for review)

- **Second shadow player that re-fetches the stream** (what the older `beamstream-app-compose` reference did: a second LibVLC re-opens the full URL, `--no-video`, RTP sout to the phone). It re-downloads the video container just for audio, and — because it is a second, independent decoder — it drifts from the main player, which is *why* Beamstream built a dead 5 Hz position-sync engine with 0.98x/1.02x speed interpolation. The tee has neither cost: no second fetch, no drift by construction. Beamstream also never shipped it: the toggle UI was unreachable (no `sheetState.show()`), pause was never propagated to the fork, port was hardcoded 5004. Worth stealing the control-plane start/ack/stop + heartbeat-teardown ideas; worth ignoring the transport core.
- **Audio-only re-fetch from the source** (server emits just the audio track; phone opens it). Engine-agnostic and therefore the natural future cover for the mpv gap (§6), but it is a second network fetch with an independent decoder (drift again), and it cannot work for torrent/localhost playback, where the media exists only inside the TV process. Keep as the complementary fallback, not the primary.

## §1 What already exists (verified)

1. **Exo is the default engine.** `PlayerRuntimeController` holds the `ExoPlayer` (`PlayerRuntimeController.kt:328`) and `currentStreamUrl` (`:199-218`); mpv is a separate engine (`NuvioMpvSurfaceView`, `:495`).
2. **The renderers are already custom-built**, in `PlayerRuntimeControllerInitialization.kt` (custom `DefaultRenderersFactory` subclass `:2097`): a `DefaultAudioSink` (`:2136-2143`) is wrapped in `PlaybackSpeedAwareAudioSink` (`:2149`) and handed to `PlaybackSpeedAwareAudioRenderer` (`:2186`), a `MediaCodecAudioRenderer` subclass.
3. **The sink already knows how to force decode-to-PCM mid-session.** `PlaybackSpeedAwareAudioSink` is a `ForwardingAudioSink` (`PlaybackSpeedAwareAudioSink.kt:18-22`) that forces the decode path (renders PCM, not bitstream) for Bluetooth output and non-1x speed — `setBluetoothForcePcm()` (`:54`) + `notifyAudioProcessingRequirementChanged()` (`:95`) flips a *live* renderer. Its renderer counterpart drops `shouldUseBypass` when forced (`PlaybackSpeedAwareAudioRenderer.kt:129-134`). This is the exact "please give me PCM on the sink now" switch the tee needs; it is a natural extension (`setPhoneForcePcm(...)`) of a class that already exists for a sibling reason.
4. **The TV-side control seam is unimplemented on purpose.** `BoomioCompanionManager.kt:288-291` — `audio_fork_*` "remain the phone remote (N2) surface" and fall through to `else -> Unit`. That block is where the handler goes.
5. **The hub contract is already in place.** `bsc/services/phone-relay.js` allow-lists `audio_fork_start`/`audio_fork_stop` (plus `stealth_audio`, `query_tracks`, `query_position`) and normalizes `phoneIp` on `audio_fork_start`; `bsc/services/device-relay.js` forwards TV `audio_fork` / `media_changed` / `companion:now_playing_changed` back to the phone; `bsc/routes/companion-api.js` sends `audio_fork_stop` to the TV when a phone's heartbeat expires. The messaging layer needs no changes.
6. **The phone already knows what the TV is playing.** `sendPlaybackPosition()` emits `positionMs` / `durationMs` / `isPlaying` / `streamUrl` / volume every ~1 s (`BoomioCompanionManager.kt:393-410`), and the hub re-exposes it via now-playing + handoff routes.
7. **mpv constraint:** the bundled libmpv (`is.xyz.mpv.BaseMPVView`) exposes **no decoded-audio callback** in its stable API — there is no sink to tee in the mpv engine. This gates §4 to the Exo engine (see §6).

## §2 Proposed design (Exo engine)

### 2.1 Tap point

Extend the existing sink stack rather than touch the renderer:

```
DefaultAudioSink  ←  PlaybackSpeedAwareAudioSink  ←  PrivateListeningAudioSink (new, ForwardingAudioSink)
```

- New `PrivateListeningAudioSink` wraps `PlaybackSpeedAwareAudioSink` (exactly as that class already wraps `DefaultAudioSink`).
- Override `handleBuffer(buffer, presentationTimeUs)`: when a phone is *armed*, copy the current `[position, limit)` slice into a lock-free ring; then forward the original buffer untouched to the inner sink. The tap happens **before** the inner sink, i.e. before Media3 applies volume in `DefaultAudioSink`'s write path — so the network copy is always full-volume audio, and muting the TV later is a separate `ExoPlayer` volume change that only affects the speaker write.
- **Force-PCM while armed.** If the sink stayed in passthrough/offload, `handleBuffer` would carry a compressed bitstream (TrueHD / AC‑4 / DTS-HD) the phone cannot decode. Reuse the existing machinery: a new `setPhoneForcePcm(true)` on `PlaybackSpeedAwareAudioSink` (mirroring `setBluetoothForcePcm`) + `notifyAudioProcessingRequirementChanged()` → the renderer drops bypass and the sink sees PCM. Because this path is already proven for Bluetooth and error-recovery, the "force decode now" transition on a live player is not new risk.
- **Renderer-thread safety:** the sink callbacks run on the ExoPlayer audio thread; the ring must be drained by a dedicated sender thread that owns the `DatagramSocket`. No network I/O on the renderer thread. Cost while armed is one memcpy of ~48 kHz×2ch×16 bit ≈ 192 KB/s.

### 2.2 Why play/pause/seek "just work" (the thing Beamstream got wrong)

Because the tee sits on the *main* decode output, it inherits the main player's clock and lifecycle:

- **Pause** → the renderer stops feeding the sink → the tee goes silent. No pause-propagation code (Beamstream's shadow player kept streaming after the main player paused).
- **Seek** → the renderer resumes at the new position; the phone hears the same content the speakers do. No seek relay, no re-sync.
- **Playback end / engine switch / source change** → tear the sender down from the same lifecycle points that already stop the player. If the session hands over to mpv (engine failover), the sender stops and the phone shows "not available with current renderer."

### 2.3 Wire format + transport

- **Direct LAN UDP unicast TV → phone**, never through the hub (the hub is a control relay and cannot carry media). The phone binds its own `DatagramSocket`, sends the actual local port up in `audio_fork_start` (the hub already relays `phoneIp`), the TV opens the tee to `phoneIp:port`. **No hardcoded port** (Beamstream's 5004 was a defect).
- **Format header:** on `configure(...)`, emit a small control datagram first (`sampleRate`, `channelCount`, `encoding`, `presentationTimeUs` base). Re-emit on any mid-stream format/track change so the phone can rebuild its `AudioTrack`.
- **Downmix to stereo in the tee copy.** Android `AudioTrack` will not downmix 7.1/5.1 to stereo for you, and decoded 7.1 PCM ≈ 6× the bandwidth. A simple channel mixer on the network copy keeps the TV's output path untouched (the AVR keeps its own channels) and halves the wire rate. The in-repo `ffmpeg-decoder-downmix` module is for a different (native decode) path — not required here.
- **Phone side (follow-on):** UDP receive + ~100 ms jitter buffer → `AudioTrack`. Plain Android; **no libVLC dependency to add** to NuvioMobile. Startup latency is a fixed ~100 ms once the buffer fills; long-term drift cannot accumulate because the source is paced by the TV's decode clock.

### 2.4 Control + lifecycle (TV side)

Fill the currently-empty `audio_fork_start` / `audio_fork_stop` handler in `BoomioCompanionManager.kt:288-291`:

- `audio_fork_start { phoneIp, port }` → arm the sink's phone tee (bind the sender; phone-side mute if requested, via existing mute/`stealth_volume` semantics at the *player* level so the tap stays live).
- `audio_fork_stop` (explicit or heartbeat-driven from the hub, which already exists) → disarm + close the sender.
- Sender lifetime is bound to the active Exo playback session, not the WS connection: it dies on `playback_stopped`, engine handover, and source change even if no stop frame ever arrives (belt-and-braces for the existing heartbeat).

## §3 Files touched (TV side, Exo)

- `ui/screens/player/PlaybackSpeedAwareAudioSink.kt` — add `setPhoneForcePcm`/arm state (parallel to Bluetooth).
- New `ui/screens/player/PrivateListeningAudioSink.kt` (tap + ring) and a small `PrivateListeningAudioSender` (drain → UDP; format headers; stereo downmix).
- `ui/screens/player/PlayerRuntimeControllerInitialization.kt` — wrap the sink stack with `PrivateListeningAudioSink` (`~:2149`).
- `core/boomio/BoomioCompanionManager.kt` — implement the `audio_fork_*` cases (`:288-291`); route start/stop to the live player's tee; tear down on playback end/engine switch.
- No new runtime deps (Media3 in-tree, `DatagramSocket`, existing ws).

## §4 Open questions for review

1. **PCM-forcing affects the TV's HDMI output while a phone is attached.** With the tee armed, TrueHD / Atmos / AC‑4 / DTS-HD are decoded to PCM (lossless-object passthrough to an AVR is suspended for the session). Roku has the same behavior. Is that acceptable as-is, or should we auto-decline phone audio when an AVR bitstream session is active? Recommend: accept + document, matching Roku.
2. **Pre-volume tap assumption** — confirm Media3 applies volume inside `DefaultAudioSink`'s write path (post-`handleBuffer`), so a pre-forward tap is unattenuated. Almost certainly true; verify at implementation with a quick unit test.
3. **Stereo-only to the phone in v1** acceptable? (Multichannel PCM passthrough to the phone is a later add; needs phone-side channel handling.)
4. **mpv-gating in v1** — private listening offered only while the Exo engine is active; mpv sessions show "unavailable with current renderer". Acceptable, given mpv engages only on startup failover or manual select? (Alternatives in §6 if not.)
5. **Security:** the audio stream is plain PCM UDP on the home LAN, gated only by the authenticated control plane (only a paired phone's `audio_fork_start`, carrying its server-normalized `phoneIp`, reaches the TV). Roku-equivalent exposure. OK for v1, SRTP/token later if we care?
6. **Same-subnet requirement:** control is hub-relayed and works across subnets; direct UDP audio does not (guest Wi‑Fi / VLAN phone). v1 should detect (compare TV LAN IP to `phoneIp`) and degrade with a clear "connect phone to the same network" message. Confirm.
7. **Sender lifecycle detail:** the tap runs only while the player screen is active; no TV-side foreground service should be required. Confirm this is acceptable for v1 (a long "leave the screen up, keep listening" session is out of scope).

## §5 Decided / deferred (v1)

- **Decided:** tee on the main Exo decode (never a second player/fetch); downmix to stereo on the network copy only; negotiated port; mute-TV via player-level volume so the tap stays live; sender bound to playback lifecycle + hub heartbeat.
- **Deferred:** mpv engine (see §6); multichannel to phone; phone cross-subnet; server audio-only refetch (mpv/torrent fallback); encryption; gapless across source change.

## §6 The mpv gap (context, not this design)

libmpv's stable API has no decoded-audio callback, so there is no Exo-style tee point in the mpv engine. If mpv coverage is ever required, the options are (a) accept Exo-only + "unavailable" state (recommended v1), (b) the engine-agnostic audio-only re-fetch (§ why-not list), or (c) patch the bundled `libmpv-android` module with a custom AO — heaviest, last resort. Not part of this proposal.

## §7 Verification

1. **Compile on the build box** (`192.168.68.63`) per the standing rule: source is a NuvioTV fork feature worktree off fork `dev`, `./gradlew :app:assembleFullRelease`, rsync with `--exclude='*/build/'`.
2. **On-device (demo Pi .53 / firestick .59):**
   - Phone toggle → audio arrives in phone earbuds; TV speakers mute when requested; video is untouched.
   - Pause/resume and scrub on the TV → phone audio follows with no drift over a 5-minute window (same-clock check).
   - Atmos/TrueHD source with phone attached → plays (decoded PCM), and reverts to passthrough the moment the phone detaches.
   - Engine-failover to mpv mid-session → phone shows the unavailable state cleanly, sender torn down.
   - Kill the phone app mid-session → hub heartbeat fires `audio_fork_stop`; TV speakers unaffected.
   - Phone on a different subnet → clear "same network" message, no partial audio.

## §8 Boundary note (phone side, separate follow-on)

NuvioMobile (`companion-bridge` branch) currently surfaces inbound `audio_fork` only as a UI event and has no audio receiver. The phone follow-on is: bind a `DatagramSocket`, send the port in `audio_fork_start`, receive PCM into a ~100 ms jitter buffer, play via `AudioTrack`, UI toggle + "mute TV" switch. No new deps, no libVLC.
