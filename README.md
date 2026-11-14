# Raw Darkroom

A small Android photo developer for the OnePlus 15. Pick photos (ideally RAW/DNG shot in the
stock camera's Master Mode), and the app tunes each one on the phone and applies a natural,
film-style look, then saves a JPEG.

No account, no cloud, no analytics, no permissions at all, including no Internet permission.
Android 11+ (API 30); targets Android 16 (API 36). Plain Java, no third-party libraries.

**Status: first version. The colour pipeline and auto-tuning are unit-tested and were checked on
sample photos on a desktop JVM. The app itself has not yet been run on a phone.** See "Known
limits" before relying on it.

This is an independent project. The look is a hand-tuned interpretation of natural colour with a
film-like highlight roll-off. It is not measured from, derived from, or affiliated with
Hasselblad's or OnePlus's processing.

## What it does

- **Open** one or many photos with the system picker: DNG, JPEG, HEIF, PNG, WebP.
- **Auto** (applied automatically when a photo is first opened): analyses the photo's brightness
  and colour statistics and sets the sliders. It is deliberately conservative: a correct-looking
  photo is left nearly alone, and warm or cool light is only partly neutralised.
- **Look** slider, 0 to 1: a gentle film S-curve (more mid-tone depth, softer toe and shoulder),
  slightly restrained colour, firmer control of neon-vivid colours, and highlights that fade
  towards white instead of clipping to a flat colour.
- **Exposure, Contrast, Highlights, Shadows, Warmth, Tint, Saturation** sliders. Tone changes act
  on luminance only, so they do not shift hue. Raising exposure rolls bright tones off smoothly.
- **Touch and hold the photo** to compare with the original.
- **Rotate** if a photo opens sideways.
- **Save / Save all**: full-resolution JPEG (quality 95) to `Pictures/RawDarkroom`, named
  `<original>_RD.jpg`. Save all auto-tunes any photo you have not opened yet. Capture time,
  camera, exposure and location metadata are copied to the export.

"Auto" is statistical image analysis, not a neural network. It needs no model file, runs in
milliseconds and its decisions are explainable (`core/AutoTune.java`).

## Install

1. Open the repository's **Actions → Build Android APK** workflow.
2. Open a successful run and download the **Raw-Darkroom-debug** artifact.
3. Unzip it, copy `app-debug.apk` to the phone and open it. Allow installation from that source
   if Android asks.

Every build is signed with the public key in `signing/`, so a new build installs over the old one.

## Known limits

- **Decoding uses Android's built-in DNG decoder**, which hands the app an already-rendered 8-bit
  image rather than the sensor's full linear data. Colour and tone editing work well on it, but
  deeply blown highlights cannot be recovered the way a true RAW converter can. A native linear
  DNG decoder is the main planned improvement.
- **RAW Max files are untested.** If Android's decoder cannot read them, the app reports that the
  photo could not be opened; regular RAW should still work.
- **No noise reduction or sharpening** yet. High-ISO RAW files will look noisier than the stock
  camera's JPEGs.
- **Orientation**: if a RAW opens sideways, use Rotate; the export follows what you see.
- A 50 MP export needs a few hundred MB of memory for a moment.
- Exports are 8-bit sRGB JPEG.

## Code map

- `core/Params.java`: one edit's slider values.
- `core/Pipeline.java`: the colour and tone maths. Pure Java, thread-safe, unit-tested.
- `core/AutoTune.java`: the on-device analysis that proposes slider values.
- `MainActivity.java`: picker, preview, sliders, export.

Run the tests with `./gradlew testDebugUnitTest`.
