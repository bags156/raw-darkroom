# Raw Darkroom

A small Android photo developer for the OnePlus 15. Pick photos (ideally RAW/DNG shot in the
stock camera's Master Mode) or share them from your gallery, and the app develops each one on
the phone with a natural, film-style look and saves a JPEG. No sliders required.

No account, no cloud, no analytics, no permissions at all, including no Internet permission.
Photos never leave the phone.
Android 11+ (API 30); targets Android 16 (API 36). Plain Java, no third-party libraries.

**Status: first version. The colour pipeline and auto-tuning are unit-tested and were checked on
sample photos on a desktop JVM. The app itself has not yet been run on a phone.** See "Known
limits" before relying on it.

This is an independent project. The look is a hand-tuned interpretation of natural colour with a
film-like highlight roll-off. It is not measured from, derived from, or affiliated with
Hasselblad's or OnePlus's processing.

## What it does

The app is built to do the work for you:

1. **Give it photos.** Tap **Open**, or pick photos in your gallery and **Share** them to Raw
   Darkroom. DNG, JPEG, HEIF, PNG and WebP all work.
2. **It develops each one automatically.** It measures the photo, then sets exposure, regional
   shadow and highlight balance, contrast and white balance, and applies the look.
3. **It saves them.** With "Save automatically" ticked (the default), full-resolution JPEGs
   (quality 95) go to `Pictures/RawDarkroom` as `<original>_RD.jpg`, with capture time, camera,
   exposure and location metadata copied across.

How the automatic development decides:

- **Exposure**: moves the photo part of the way towards a normal mid-tone. A dark scene whose
  brightest parts are already bright (dusk, night, a stage) is treated as intentionally dark.
- **Shadows and highlights by region**: a blurred brightness map of the photo lets it lift a dark
  area or hold back a bright sky as a whole, so the detail inside keeps its contrast. This is what
  keeps the result natural rather than flat. Pure black stays black and clipped white stays white.
- **White balance**: corrects only about a third of any colour cast, and ignores small ones. The
  camera is usually right, and warm or cool light is often the point of the photo.
- **The look**: a gentle film S-curve (more mid-tone depth, softer toe and shoulder), slightly
  restrained colour, firmer control of neon-vivid colours, and highlights that fade towards white
  instead of clipping to a flat colour.

When a photo needs a nudge:

- **Touch and hold the photo** to compare with the original.
- **Adjust** reveals sliders (Look, Exposure, Contrast, Highlights, Shadows, Warmth, Tint,
  Saturation) plus **Auto** and **Reset**. **Save** writes the adjusted version as a new file.
- **Rotate** if a photo opens sideways.
- Untick "Save automatically" to review first and use **Save** / **Save all** yourself.

The automatic development is statistical image analysis, not a neural network. It needs no model
file, runs in milliseconds, and its decisions are explainable (`core/AutoTune.java`).

## Install

**With Obtainium:** add `https://github.com/bags156/raw-darkroom` as an app source. Each release
carries a signed APK, so updates install in place.

**By hand:** open the repository's **Releases** page, download `Raw-Darkroom-vX.Y.Z.apk` from the
latest release and open it on the phone. Allow installation from that source if Android asks.

Every build is signed with the public key in `signing/`, so a new build installs over the old one.

To publish a new release, change `version.txt` (for example to `0.2.0`) and push to `main`.

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
- Opening the same photos again with automatic saving on saves them again (Android adds a number
  to the file name).
- Very strong shadow or highlight settings can leave a faint glow along hard edges between very
  dark and very bright areas. The automatic settings stay below that range.
- A 50 MP export needs a few hundred MB of memory for a moment.
- Exports are 8-bit sRGB JPEG.

## Code map

- `core/Params.java`: one edit's slider values.
- `core/Pipeline.java`: the colour and tone maths. Pure Java, thread-safe, unit-tested.
- `core/LocalMap.java`: the blurred brightness map behind regional shadows and highlights.
- `core/AutoTune.java`: the on-device analysis that proposes slider values.
- `MainActivity.java`: picker, share target, preview, sliders, export.

Run the tests with `./gradlew testDebugUnitTest`.
