# Private Listening over an ExoPlayer audio-sink tee (NuvioTV)

> **Status: SENIOR-DEV SIGN-OFF (2026-09-04, PR #10 review) — ready for owner go, then implementation.** No code changes yet.
>
> Implementation will be a **fresh feature branch off fork `dev`** (now at the post-PR-#11 tip) — not off this docs branch — compiled only on the build box (`192.168.68.63`, `:app:assembleFullRelease`). Doc-only branch: `docs/private-listening-exo-tee` (PR #10, draft).

## Review outcome (senior dev, PR #10 — 2026-09-04)

**Verdict: sound — sign off.** Every load-bearing claim was verified against fork `dev` and boomio `origin/main` ("including the 'already exists' inventory, which is the whole premise"). Sharpening notes and the decisions they produce are folded into the body below and marked **(R#n)**; none block. Reviewer noted doc line numbers were accurate enough to re-anchor without hunting.

Binding outcomes from the review:

- **(R2 — speed, not volume, is the real pre-tap subtlety.)** Media3 applies volume at `AudioTrack.setVolume`/AudioFlinger, *not* baked into the sink buffer — so a `handleBuffer` tap is full-volume regardless of where it sits. But playback speed is applied by **SonicAudioProcessor inside `DefaultAudioSink`**, so an outer-wrapper tee sees **pre-Sonic PCM**: the phone copy is accurate at **1x**, and would silently drift at 1.2x. Decided: v1 documents **"phone copy accurate at 1x"**; verify Sonic's position once at implementation (it is identity at 1x, so likely no code).
- **(R3 — `phoneIp` is phone-supplied, not server-verified.)** `phone-relay.js:98` only defaults `phoneIp` to null when absent; the hub does not derive it from the socket peer. So the NuvioMobile follow-on **must send its actual LAN IP** in `audio_fork_start` (else the TV has nowhere to send), and the v1 security model is "any device that completes pairing can point the TV's audio stream at a LAN address of its choosing" — Roku-equivalent, LAN-only, accepted (R5).
- **(R1/R5 — PCM-forcing is bounded.)** The force-decode latch applies only while a phone is *armed*; it releases when the fork stops, so HDMI passthrough returns **without a player rebuild** — the Bluetooth path already proves the un-latch (`setBluetoothForcePcm(false)` + `notifyAudioProcessingRequirementChanged`). AVR bitstream suspension while active: accepted + documented.
- **(R4 — A/V offset decided, not discovered.)** The phone copy carries ~100 ms of network + jitter-buffer latency that the TV's (muted) path does not. **Decided: accept for v1** (Roku-tolerated), with an optional small video-renderer delay kept as the known lever if on-device lip-sync testing objects.
- **(R6 — downmix must preserve dialogue.)** Stereo-only v1 accepted; use a dialogue-preserving 6→2 matrix (Lt/Rt-style or the `ffmpeg-decoder-downmix` module's coefficients as reference), and open the phone's `AudioTrack` in low-latency mode (`AudioAttributes` + small buffer) or the jitter buffer fights it.
- **(R-gap — mute ownership + crash restore + visibility.)** The TV-side mute is player-level volume so the tap stays live; add the invariant that a TV process death while muted comes back **volume-restored** (volume is per-player-session, so likely free — state it, don't discover it at WS), and surface the TV-side "phone attached" indicator in the existing diagnostics so a WS4 debug isn't blind.

## The product model

Roku Private Listening: the *playing device* is already decoding the audio that drives its own speakers; private listening **tees that decoded audio** and streams a copy over Wi‑Fi to the phone. It never re-opens the video, never runs a second decoder, and never needs the phone to fetch anything. A/V sync is free because the copy shares the main player's decode clock.

NuvioTV already has every prerequisite for this exact model (§1). The design is a tee wrapped around an audio sink the app *already* customizes, riding a message contract the hub *already* allow-lists, using a phone-supplied IP the hub *already* relays to the TV.

## Why not the obvious alternatives (context for review)

- **Second shadow player that re-fetches the stream** (what the older `beamstream-app-compose` reference did: a second LibVLC re-opens the full URL, `--no-video`, RTP sout to the phone). It re-downloads the video container just for audio, and — because it is a second, independent decoder — it drifts from the main player, which is *why* Beamstream built a dead 5 Hz position-sync engine with 0.98x/1.02x speed interpolation. The tee has neither cost: no second fetch, no drift by construction. Beamstream also never shipped it: the toggle UI was unreachable (no `sheetState.show()`), pause was never propagated to the fork, port was hardcoded 5004. Worth stealing the control-plane start/ack/stop + heartbeat-teardown ideas; worth ignoring the transport core.
- **Audio-only re-fetch from the source** (server emits just the audio track; phone opens it). Engine-agnostic and therefore the natural future cover for the mpv gap (§6), but it is a second network fetch with an independent decoder (drift again), and it cannot work for torrent/localhost playback, where the media exists only inside the TV process. Keep as the complementary fallback, not the primary.

## §1 What already exists (verified)

1. **Exo is the default engine.** `PlayerRuntimeController` holds the `ExoPlayer` (`PlayerRuntimeController.kt:328`) and `currentStreamUrl` (`:199-218`); mpv is a separate engine (`NuvioMpvSurfaceView`, `:495`).
2. **The renderers are already custom-built.** Custom `DefaultRenderersFactory` subclass (`PlayerRuntimeControllerInitialization.kt:2074`) builds a `DefaultAudioSink` (`:2113-2132`), wraps it in `PlaybackSpeedAwareAudioSink`, and hands it to `PlaybackSpeedAwareAudioRenderer`, a `MediaCodecAudioRenderer` subclass (`PlaybackSpeedAwareAudioRenderer.kt:27`). A ready wiring hook for a new outer sink exists: `onPlaybackSpeedAwareAudioSinkCreated` (`PlayerRuntimeControllerInitialization.kt:837, :2072`).
3. **The sink already knows how to force decode-to-PCM mid-session.** `PlaybackSpeedAwareAudioSink` is a `ForwardingAudioSink` wrapping `DefaultAudioSink` (`PlaybackSpeedAwareAudioSink.kt:18-22`). `shouldRejectDirectPlayback` (`:117-127`) makes the renderer refuse bitstream when `bluetoothForcePcm || forcePcmForCurrentSession` or speed ≠ 1; `configure` latches it; `notifyAudioProcessingRequirementChanged()` → `onAudioCapabilitiesChanged()` forces Media3 to reselect on a live renderer. A `setPhoneForcePcm` mirroring `setBluetoothForcePcm` (`:54-65`) slots in with zero new machinery. The renderer independently drops `shouldUseBypass` when forced (`PlaybackSpeedAwareAudioRenderer.kt:129-133`) and reports `AUDIO_OFFLOAD_NOT_SUPPORTED` (`:136-138`). (R1/R5)
4. **The TV-side control seam is unimplemented on purpose.** `BoomioCompanionManager.kt:288-291` — `audio_fork_*` "remain the phone remote (N2) surface" and fall through to `else -> Unit`. That block is where the handler goes.
5. **The hub contract is already in place.** `bsc/services/phone-relay.js:30` allow-lists `audio_fork_start`/`audio_fork_stop` (+ `stealth_audio`, `query_tracks`, `query_position`); `:98` touches `phoneIp` on `audio_fork_start` (defaults to null when absent — **not** server-derived, R3); `bsc/services/device-relay.js:166-168` forwards TV `audio_fork` / `media_changed` / `companion:now_playing_changed` back to the phone; `bsc/routes/companion-api.js:106` sends `audio_fork_stop { reason: 'phone_timeout' }` to the TV when a phone's 30 s heartbeat expires (5 s sweep). The messaging layer needs no changes.
6. **The phone already knows what the TV is playing.** `sendPlaybackPosition()` emits `positionMs` / `durationMs` / `isPlaying` / `streamUrl` / volume every ~1 s (`BoomioCompanionManager.kt:393-410`), and the hub re-exposes it via now-playing + handoff routes.
7. **mpv constraint:** the bundled libmpv (`is.xyz.mpv.BaseMPVView`) exposes **no decoded-audio callback** in its stable API — there is no sink to tee in the mpv engine. Gates §2 to the Exo engine (see §6). (R4 answers accept this.)
8. **Lifecycle premise (verified):** on-device Exo playback has **no** general foreground service — `ExternalPlaybackKeepAliveService` is external-playback-only and feature-flagged — so the sender living and dying with the player screen is the app's existing model, not a new limitation. (R7)

## §2 Proposed design (Exo engine)

### 2.1 Tap point

Extend the existing sink stack rather than touch the renderer:

```
DefaultAudioSink  ←  PlaybackSpeedAwareAudioSink  ←  PrivateListeningAudioSink (new, ForwardingAudioSink)
```

- New `PrivateListeningAudioSink` wraps `PlaybackSpeedAwareAudioSink` (exactly as that class already wraps `DefaultAudioSink`); wire it through the existing `onPlaybackSpeedAwareAudioSinkCreated` hook rather than threading new constructor plumbing.
- Override `handleBuffer(buffer, presentationTimeUs)`: when a phone is *armed*, copy the current `[position, limit)` slice into a lock-free ring; then forward the original buffer untouched to the inner sink. The tap is full-volume by construction — Media3 applies volume at `AudioTrack.setVolume`/AudioFlinger, not in the sink buffer (R1) — so the network copy is always unattenuated, and muting the TV later is a separate player-level volume change that only affects the speaker write. (R-gap: mute ownership.)
- **1x-only accuracy (R2):** at speed ≠ 1 Media3 time-stretches inside `DefaultAudioSink` (SonicAudioProcessor), *after* this wrapper — so the phone copy is pre-Sonic and accurate **at 1x only**. Document "phone copy accurate at 1x" as a v1 property; if the post-Sonic position turns out to be needed, verify once at implementation (identity at 1x, so likely no code).
- **Force-PCM while armed.** If the sink stayed in passthrough/offload, `handleBuffer` would carry a compressed bitstream (TrueHD / AC‑4 / DTS-HD) the phone cannot decode. Reuse the existing machinery: a `setPhoneForcePcm(true)` mirroring `setBluetoothForcePcm` + `notifyAudioProcessingRequirementChanged()` → the renderer drops bypass and the sink sees PCM. The latch is **bounded to the armed session** and releases when the fork stops, restoring passthrough without a player rebuild (R5).
- **Renderer-thread safety:** the sink callbacks run on the ExoPlayer audio thread; the ring must be drained by a dedicated sender thread that owns the `DatagramSocket`. No network I/O on the renderer thread. Cost while armed is one memcpy of ~48 kHz×2ch×16 bit ≈ 192 KB/s.

### 2.2 Why play/pause/seek "just work" (the thing Beamstream got wrong)

Because the tee sits on the *main* decode output, it inherits the main player's clock and lifecycle:

- **Pause** → the renderer stops feeding the sink → the tee goes silent. No pause-propagation code (Beamstream's shadow player kept streaming after the main player paused).
- **Seek** → the renderer resumes at the new position; the phone hears the same content the speakers do. No seek relay, no re-sync.
- **Playback end / engine switch / source change** → tear the sender down from the same lifecycle points that already stop the player. If the session hands over to mpv (engine failover), the sender stops and the phone shows "not available with current renderer."
- **Explicit product limit (R7):** phone audio stops if the user leaves the player screen (sender is player-screen-bound; consistent with the app's no-foreground-service playback model).

### 2.3 Wire format + transport

- **Direct LAN UDP unicast TV → phone**, never through the hub (the hub is a control relay and cannot carry media). The phone binds its own `DatagramSocket`, and **sends its actual local LAN IP + port** in `audio_fork_start` — the hub relays `phoneIp` as supplied, it does **not** derive it (R3). The TV opens the tee to `phoneIp:port`. **No hardcoded port** (Beamstream's 5004 was a defect).
- **Format header:** on `configure(...)`, emit a small control datagram first (`sampleRate`, `channelCount`, `encoding`, `presentationTimeUs` base, and the active playback speed so the phone can apply it if non-1x in a later revision). Re-emit on any mid-stream format/track change so the phone can rebuild its `AudioTrack`.
- **Downmix to stereo in the tee copy, dialogue-preserving (R6).** Android `AudioTrack` will not downmix 7.1/5.1 to stereo for you, and decoded 7.1 PCM ≈ 6× the bandwidth. Use a proper 6→2 matrix (Lt/Rt-style or the in-repo `ffmpeg-decoder-downmix` module's coefficients as reference) so dialogue (center) is not ducked — not a naive coefficient sum. The downmix touches only the network copy; the TV's output path keeps its own channels. **Phone side:** open `AudioTrack` in low-latency mode (`AudioAttributes` + small buffer) or the jitter buffer will fight it. (R6)
- **Phone side (follow-on):** UDP receive + ~100 ms jitter buffer → `AudioTrack`. Plain Android; **no libVLC dependency to add** to NuvioMobile. Startup latency is a fixed ~100 ms once the buffer fills; long-term drift cannot accumulate because the source is paced by the TV's decode clock.

### 2.4 Control + lifecycle (TV side)

Fill the currently-empty `audio_fork_start` / `audio_fork_stop` handler in `BoomioCompanionManager.kt:288-291`:

- `audio_fork_start { phoneIp, port }` → arm the sink's phone tee (bind the sender). Mute-TV-when-requested is a **player-level volume** change (tap stays live).
- `audio_fork_stop` (explicit or heartbeat-driven from the hub, which already exists) → disarm + close the sender.
- **Crash-restore invariant (R-gap):** TV volume is per-player-session, so a TV process death while muted comes back volume-restored; state this invariant explicitly so it is not discovered at WS. 
- **Diagnostics (R-gap):** add the TV-side "phone attached / audio forking" state to the existing diagnostics surface so WS4 debugging isn't blind.
- Sender lifetime is bound to the active Exo playback session, not the WS connection: it dies on `playback_stopped`, engine handover, and source change even if no stop frame ever arrives (belt-and-braces for the existing heartbeat).

## §3 Files touched (TV side, Exo)

- `ui/screens/player/PlaybackSpeedAwareAudioSink.kt` — add `setPhoneForcePcm`/arm state (parallel to Bluetooth).
- New `ui/screens/player/PrivateListeningAudioSink.kt` (tap + ring) and a small `PrivateListeningAudioSender` (drain → UDP; format headers; dialogue-preserving stereo downmix).
- `ui/screens/player/PlayerRuntimeControllerInitialization.kt` — wrap the sink stack with `PrivateListeningAudioSink` via `onPlaybackSpeedAwareAudioSinkCreated` (`:837, :2072`).
- `core/boomio/BoomioCompanionManager.kt` — implement the `audio_fork_*` cases (`:288-291`); route start/stop to the live player's tee; tear down on playback end/engine switch; expose "phone attached" in diagnostics.
- No new runtime deps (Media3 in-tree, `DatagramSocket`, existing ws).

## §4 Review decisions (from the PR #10 open questions — all resolved)

| # | Question | Decision (review) |
|---|----------|--------------------|
| 1 | PCM-forcing suspends TrueHD/Atmos/AC-4 bitstream to an AVR while a phone is attached | **Accept + document**; bounded to the armed session (R5) |
| 2 | Is the pre-forward tap unattenuated? | **Resolved (R1):** yes — volume is at `AudioTrack`, not in the buffer; no unit test needed |
| 3 | Stereo-only v1? | **Accept** (R6), dialogue-preserving matrix |
| 4 | mpv-gating in v1? | **Accept**; mpv is failover/manual-only today; §6 options are the backlog |
| 5 | Plain PCM UDP, gated by authenticated control? | **Accept** with the R3 correction (`phoneIp` phone-supplied) |
| 6 | Same-subnet requirement? | **Agree** — TV reads its WS local address to compare against `phoneIp`; clear "same network" message |
| 7 | No foreground service, player-screen-bound sender? | **Accept**; state it as an explicit product limit |

## §5 Decided / deferred (v1)

- **Decided:** tee on the main Exo decode (never a second player/fetch); force-PCM only while armed (bounded, reversible without rebuild); stereo dialogue-preserving downmix on the network copy only; negotiated port; phone-supplied LAN IP; mute-TV via player-level volume with the crash-restore invariant; sender bound to playback lifecycle + hub heartbeat; phone copy accurate at 1x; ~100 ms A/V offset accepted (video-delay lever documented); diagnostics surface shows "phone attached".
- **Deferred:** mpv engine (see §6); multichannel to phone; non-1x phone copy; phone cross-subnet; server audio-only refetch (mpv/torrent fallback); encryption; gapless across source change.

## §6 The mpv gap (context, not this design)

libmpv's stable API has no decoded-audio callback, so there is no Exo-style tee point in the mpv engine. Options, in backlog order: (a) Exo-only + "unavailable" state (v1, accepted), (b) the engine-agnostic audio-only re-fetch, (c) patch the bundled `libmpv-android` module with a custom AO — heaviest, last resort. Not part of this proposal.

## §7 Verification

1. **Compile on the build box** (`192.168.68.63`) per the standing rule: source is a NuvioTV fork feature worktree **off fork `dev` at the post-PR-#11 tip**, `./gradlew :app:assembleFullRelease`, rsync with `--exclude='*/build/'`.
2. **On-device (demo Pi .53 / firestick .59):**
   - Phone toggle → audio arrives in phone earbuds; TV speakers mute when requested; video is untouched.
   - Pause/resume and scrub on the TV → phone audio follows with no drift over a 5-minute window (same-clock check).
   - **1x speed accuracy (R2):** phone copy matches the TV at 1x; set 1.2x → confirm the documented behavior (phone stays at 1x / sender marks non-1x), no silent divergence.
   - Atmos/TrueHD source with phone attached → plays (decoded PCM); detach phone → passthrough returns with no player rebuild (R5).
   - **Dialogue check (R6):** a 5.1 scene with center-channel dialogue stays intelligible in the phone stereo downmix.
   - Engine-failover to mpv mid-session → phone shows the unavailable state cleanly, sender torn down.
   - Kill the phone app mid-session → hub heartbeat fires `audio_fork_stop`; TV speakers unaffected.
   - **Crash-restore (R-gap):** kill the TV app mid-muted-private-listening → relaunch comes back volume-restored.
   - Phone on a different subnet → clear "same network" message, no partial audio.

## §8 Boundary note (phone side, separate follow-on)

NuvioMobile (`companion-bridge` branch) currently surfaces inbound `audio_fork` only as a UI event and has no audio receiver. The phone follow-on is: bind a `DatagramSocket`, **send the phone's actual LAN IP + local port in `audio_fork_start`** (R3), receive PCM into a ~100 ms jitter buffer, play via low-latency `AudioTrack`, UI toggle + "mute TV" switch. No new deps, no libVLC.
