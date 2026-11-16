<div align="center">

# 🎚️ MusicCut

**An open-source Android multitrack audio workstation**

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
[![CI](https://github.com/homesweetlove/MusicCut/actions/workflows/android.yml/badge.svg)](https://github.com/homesweetlove/MusicCut/actions/workflows/android.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

[**English**](README.md) · [**한국어**](README.ko.md) · [**Releases**](https://github.com/homesweetlove/MusicCut/releases) · [**Build status**](https://github.com/homesweetlove/MusicCut/actions)

</div>

MusicCut combines multitrack editing, DSP effects, recording, and optional on-device AI stem separation. The source code is published independently of the original private development history.

> **Status:** Open-source development preview. Core tests and Android builds are automated; real-device behavior, model downloads, and Google Drive authentication may require additional setup or testing. There is no production-signed APK until a private signing key is configured.
>
> MusicCut is an independent project and is **not affiliated with Apple, Logic Pro, or the creators of third-party separation models**. Product names are used only to describe compatibility or inspiration.

## Install

- [**Download a signed APK from Releases (when available)**](https://github.com/homesweetlove/MusicCut/releases)
- For development builds: open [Android CI](https://github.com/homesweetlove/MusicCut/actions/workflows/android.yml), choose a successful run, and download the `musiccut-debug-apk` artifact (GitHub sign-in may be required).
- Development APKs use ephemeral debug signing identities; a later build may require uninstalling the previous debug version. Back up projects first.

## At a glance

| Area | Highlights |
| --- | --- |
| Multitrack editor | Waveforms, split/cut/join, ripple editing, undo/redo, automation |
| Audio engine | Kotlin DSP effects, mixer, Aux buses, offline rendering |
| AI separation | UVR MDX-Net / Kim Vocal 2 and HT-Demucs via on-device ONNX Runtime; model downloads are optional |
| Export | WAV, MP3, AAC, share/save locally |
| Google Drive | Optional `drive.file` OAuth integration, needs matching Google Cloud configuration |

## Main Features

### Editing (Cut / Join)

| Feature | Description |
|---|---|
| Multitrack timeline | Waveforms, bars/beats ruler, pinch zoom, playhead auto-scroll |
| Three editing tools | **Pointer** (select/move/resize/fade), **Range** (time-range selection), **Scissors** (split at tap position) |
| Cut / Cut-to-clipboard / Copy / Paste | Paste overwrites; **Insert** pushes later content forward |
| Ripple delete | Remove a middle section and automatically pull later material forward |
| Keep selection only / Insert silence / Split at boundaries | Applied to the range selected with the Range tool |
| Join | Contiguous pieces join losslessly; other material is rendered into new audio before joining |
| Duplicate, reverse, normalize, region gain, fade in/out, region mute | |
| Snap | Off / Bar / 1/4 / 1/8 / 1/16, based on tempo |
| Undo / Redo | All editing is non-destructive; original source audio remains unchanged |
| Arrangement sections | Reorder, duplicate/repeat, or delete Intro / Verse / Chorus sections while moving every track and marker together |
| Markers | Add at playhead, tap to jump, move to previous/next marker |
| Nudge | Move the selected region by one grid unit, or 10 ms when snap is off |
| Cycle playback, recording (overdub + 1-bar count-in), metronome, track M/S/R, track colors | |

### Plugins (Logic-Style Stock Set, 41 Types)

Each track and the master Stereo Out can use an **unlimited insert chain**, with reordering, bypass, and preset support.

| Category | Plugins |
|---|---|
| EQ | **Channel EQ** (draggable bands + analyzer), Silver EQ, **Graphic EQ** (31-band), **Match EQ** (reference learning) |
| Dynamics | **Compressor** (Platinum/VCA/FET/Opto/Classic circuits, sidechain), **Limiter**, **Adaptive Limiter**, **Noise Gate** (sidechain), **DeEsser 2**, **Multipressor**, **Enveloper** |
| Reverb | **ChromaVerb**, **Space Designer** (convolution: Room/Hall/Plate/Chamber/Cathedral/Spring) |
| Delay | **Tape Delay**, **Stereo Delay** (tempo sync), **Delay Designer** (4-tap), **Sample Delay** |
| Modulation | **Chorus**, **Ensemble**, **Flanger**, **Phaser**, **Tremolo**, **Ringshifter** |
| Distortion | **Overdrive**, **Distortion**, **Bitcrusher**, **Exciter**, **ChromaGlow**, **Amp Designer** |
| Filter | **AutoFilter** (LFO/envelope), High/Low Cut |
| Pitch | **Pitch Shifter**, **Pitch Correction** (Auto-Tune style), **Vocal Transformer** (pitch + formant) |
| Imaging / Utility | **Direction Mixer**, **Stereo Spread**, **Gain**, **Loudness Meter**, **MultiMeter**, **Tuner**, **Test Oscillator** |

Aux send/return buses are supported. Choosing “New Aux + ChromaVerb” from a mixer Send slot creates a reverb bus in a Logic-like workflow.

New plugins can be added by inheriting `AudioPlugin` and registering with `PluginRegistry.register(...)`.

### Vocal / Instrumental Separation (AI)

Select a region and press **Vocal Separation** to create new `Vocals` and `Inst` tracks. Choose from three engines:

| Engine | Description |
|---|---|
| **AI · UVR MDX-Net Voc FT** (default) | Runs Ultimate Vocal Remover's MDX-Net ONNX model directly on the phone with ONNX Runtime. The model (~64 MB) is downloaded only once and works offline afterward. |
| AI · Kim Vocal 2 | Another trained model using the same pipeline structure |
| Fast Separation | STFT stereo-center extraction; lightweight and fast but lower quality |

The AI pipeline (STFT 7680 / hop 1024 / 3072×256 tensor with chunk overlap) is tested against the Python reference implementation with an error tolerance of about 2×10⁻⁷.

### Flex · Tempo · Pitch

- **Time stretch** with pitch preservation using a phase vocoder, **Varispeed**, and **pitch shifting** while preserving duration
- **Tempo detection** → set project tempo, match regions to project tempo, automatically tempo-match imported loops
- **Flex Pitch**: detect vocal notes and display them in a piano roll → drag to correct pitch or use “Fit to Key”
- **Pitch Correction** plugin: real-time tuning with key/scale/response-speed controls

### Mixing

- **Automation**: volume, pan, sends, and all plugin parameters. Press `A` to show automation, tap to add points, drag to move, long-press to delete. **Latch Write** records fader/knob motion during playback.
- **Sidechain**: key compressors/noise gates from another track for techniques such as kick ducking
- **Summing stacks / output routing**, **groups** for linked volume/mute/solo, and Pre/Post Aux sends
- **LUFS loudness**: Loudness Meter plugin plus export normalization to -14 / -16 / -9 LUFS with resulting LUFS and True Peak display

### Recording · Takes

- 1-bar count-in and metronome
- Recording again over the same range creates a **take folder**, allowing full-take or range-based comping

### Files

- Export: **MP3 (320/192k)**, WAV 16/24/32f, M4A (AAC)
- **Autosave** every minute + automatically reopen the last project on launch
- **Loop / media browser**: choose a folder, audition files, and insert them with automatic tempo matching

### Google Drive Integration

- **Export → Upload to Google Drive**: bounce the mix as WAV (16/24/32f) or M4A (AAC) and upload it to a `MusicCut AI` folder in Drive
- **Back up project to Drive**: upload project JSON plus all audio files
- Saving through Android SAF and sharing to other apps are also supported

## Structure

```
core/   Pure Kotlin (no Android dependency; tested on the JVM)
  audio/       AudioBuffer, WAV codec, high-quality resampler
  dsp/         Biquad, SVF, delay line, FFT, envelope
  plugin/      plugin API, registry, chain, frequency-response analysis
  plugin/effects/  26 effect implementations
  project/     Project / Track / Region models (JSON serialization), source pool, waveform peaks
  edit/        non-destructive editing operations (split, ripple cut/insert, paste, trim, join…), section/marker editing, Undo
  engine/      mixer (region → inserts → sends → volume/pan → Aux → master), metronome, offline bounce
  separation/  vocal/instrumental separation
app/    Android (Jetpack Compose)
  audio/       MediaCodec decoder, AudioTrack playback engine, AudioRecord recording, AAC encoder
  drive/       Google authentication + Drive v3 resumable upload
  data/        project save/load
  ui/          timeline, mixer, plugin editors, dialogs
```

The playback engine mixes in 32-bit float on a dedicated high-priority thread. Editing, fader changes, and plugin parameter changes are applied immediately during playback.

Project audio is stored internally as 32-bit float WAV, so repeated editing does not degrade audio quality.
## Build and tests

Requires Android SDK 35 and JDK 17. No Google credentials or release keys are needed for a local debug build.

```bash
./gradlew :core:test :app:assembleDebug
```

GitHub Actions checks the core tests and Android APK on every change; build artifacts are **development-only**, not production releases. To publish a signed release, see [release instructions](docs/RELEASE.md).

### Google Drive setup

Enable the Google Drive API and create an **Android OAuth client** with package `com.musiccut.app` and the **SHA-1 fingerprint of your own signing certificate**. The previous private project's signing fingerprint is intentionally not reused. Access uses the `drive.file` scope; users must grant access in the app.

### Third-party models and trademarks

Models are downloaded separately from their original hosts; they are **not included in the repository or APK**. See [model attribution and licensing](THIRD_PARTY_NOTICES.md). Their licenses and terms apply independently from MusicCut's code license. Only process audio you are authorized to use.

