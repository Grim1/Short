# Short: how it works inside

This is the technical companion to the [README](../README.md).

## The idea

Glitches in Short are faults inside a simulated camera, not filters laid over a finished picture. The camera image passes through six stages, in the order a real digital camera handles it. A wire on the board sets a fault in one stage, and every later stage then works on the damaged signal.

| # | Stage | What it stands for |
|---|---|---|
| 1 | Sensor readout | Lines and columns clocked off the sensor |
| 2 | A/D converter | Analogue levels turned into bits |
| 3 | Frame memory | RAM holding the current frame |
| 4 | Colour encoder | Brightness and colour combined |
| 5 | Clock and sync | Timing for the whole chain |
| 6 | Compressor | JPEG compression |

A body does not have to have every stage. The camcorder has no compressor; the toy has neither a colour encoder nor a compressor.

## Modules

| Module | What it holds |
|---|---|
| `engine` | The signal chain: an OpenGL ES 3.0 renderer on its own thread, one shader pass per stage, plus drift and crashes |
| `bodies` | The body package format and its loader, which validates a package and reports every problem in it |
| `board` | The opened back: the board drawing, wires with their physics, pots, switches, contact pads |
| `capture` | Saving: JPEG stills with real byte corruption, the GIF encoder, the video recorder |
| `app` | The screens: start animation, shelf, camera front and back, notebook, mods |

Adding a camera needs no code changes in `engine`: a body is data.

## The engine

- **Input.** CameraX delivers preview frames to a `SurfaceTexture`. The first pass crops and turns them into the body's sensor shape, and applies zoom the phone's own lens could not give.
- **Orientation.** The chain always runs in sensor (landscape) orientation. Held in portrait, the sensor is sideways, as in a real camera, so scan-line faults run vertically in portrait photos. The frame is turned upright only when shown or saved.
- **Passes.** Each stage is one fragment shader drawing into one of two ping-pong framebuffers. Memory and sync also read their own previous output, which is how trails, stale blocks, feedback and field slip work. A stage with nothing bent is skipped.
- **Fault grid.** Faults are quantised to the viewfinder's resolution and all their randomness is derived from the frame time and grid position. A full-resolution still re-renders the frame that was in the viewfinder with the same time and fault values, so the photo shows the fault you saw.
- **Fault strength.** Each fault is a number from 0 to 1. Low values fire only some of the time (a weak contact flickers); from a little under half way it holds steady and gets stronger.
- **Drift.** Every active fault wanders slowly around the value it was set to. Less stable bodies wander further.
- **Crashes.** A crash freezes the frame, garbles it, goes dark, then restarts with the wires intact. Crashes come from wiring a crash pair, or from overload: the total of all active faults (clock and memory faults count for more) against a capacity set by the body's stability.

## Faults

| Stage | Fault key | What it does |
|---|---|---|
| Sensor | `uLineSkip` | Lines repeat or are skipped |
| Sensor | `uColumnShift` | Bands of lines start at the wrong column |
| Sensor | `uSmear` | Bright lights streak down their column |
| Sensor | `uStuck` | Pixels and whole columns stuck on or dead |
| Sensor | `uWobble` | The picture sways like jelly |
| A/D | `uBitCrush` | Fewer brightness steps |
| A/D | `uBitSwap` | Data lines crossed, colours scramble |
| A/D | `uFold` | Bright tones fold back inverted |
| A/D | `uChannelDrop` | One colour channel reads nothing or full scale, in bands |
| Memory | `uAddrShort` | The picture repeats in tiles |
| Memory | `uStale` | Blocks keep what they last held |
| Memory | `uTrails` | Old frames bleed into new ones |
| Memory | `uFeedback` | The frame is read back into itself and tunnels outward |
| Memory | `uMirror` | The picture folds onto itself |
| Encoder | `uChromaSwap` | The two colour signals swap |
| Encoder | `uChromaDelay` | Colour slides off the brightness it belongs to |
| Encoder | `uChromaDrift` | Hue rotates over time and down the frame |
| Encoder | `uHueSpin` | Every hue turns round the colour wheel together |
| Encoder | `uColorKill` | Colour cuts out to grey in bands |
| Sync | `uTear` | The picture tears sideways |
| Sync | `uRoll` | The picture rolls vertically |
| Sync | `uStarve` | Frames hang and lines drop out |
| Sync | `uTracking` | A band of torn, snowy lines wanders the frame |
| Sync | `uComb` | Every other line lags, edges comb |
| Sync | `uDropout` | White streaks and a torn bottom edge |
| Compressor | `uBlockCorrupt` | Blocks break up and shift |
| Compressor | `uQuant` | Detail goes coarse and blocky |

## Body packages

A body lives in `app/src/main/assets/bodies/<id>/body.json`. The loader checks the whole file and lists every problem it finds. A package sets:

- **Identity and look:** maker, model, slogans printed on the shell, serial, shell and ink colours.
- **Viewfinder:** on-screen text for each corner, its font and colour, the shutter sound (synthesised from length, noise and beep pitch), and the start-up screen.
- **Camera:** sensor and viewfinder size, bit depth, which stages it has, stability, colour softness, zoom range.
- **Board:** size, colour, where the small screen sits, and every part drawn on it. Optional image layers can replace the code-drawn board.
- **Pads:** id, silkscreen label, position, the stage each belongs to, and whether it is a finger contact.
- **Fault table:** which pad pair drives which fault, with the range a pot sweeps and the strength of a bare wire; or that the pair crashes the camera.
- **Contacts:** which fault each finger contact drives.
- **Crosstalk:** how strongly pairs the table does not list still couple. Such a pair gets a weaker fault chosen from the stages its two pads sit on. The choice is fixed by the pair, so it is the same every time.

Pads carry only silkscreen labels, never effect names.

## Saving

- **Stills** are rendered at the body's sensor size, encoded as JPEG in sensor orientation with an EXIF orientation tag. The compressor stage is not drawn into a still. Its faults are applied to the file itself: quality is forced down for `uQuant`, and bytes of the encoded stream are corrupted for `uBlockCorrupt`. Every corrupted file is checked to still decode, and retried if not.
- **The patch** (every wire, its control and setting) is stored as JSON in the photo's EXIF image description, with the body as make and model. Loading a patch from a photo reads it back.
- **GIFs** are written by a small encoder in `capture`: a 256-colour palette per frame chosen by median cut, and LZW compression. GIFs do not carry the patch.
- **Long exposures** combine viewfinder frames (half the average, half the brightest value each pixel reached), so they are viewfinder size. **Double exposures** add two full-size stills.
- **Video** is H.264 with AAC sound in an MP4, recorded from the viewfinder frames at 30 frames a second. The sound is not bent.
- **Mods** are patch files in the app's private storage, one folder per body. The **notebook** is a list of discovered pad pairs per body, stored the same way.

## Building and testing

- Kotlin, Jetpack Compose (foundation only), CameraX, OpenGL ES 3.0. Minimum Android 12 (API 31).
- Build: `./gradlew :app:assembleDebug`
- Tests: `./gradlew :capture:testDebugUnitTest` runs the GIF encoder tests, which write GIFs and read them back with the JDK's decoder. There are no other automated tests.
- Tested by hand on one Samsung tablet only.

## Known gaps

- Wires on a board are lost when the app closes unless saved as a mod.
- GIFs and video do not carry the patch.
- The release build (minified) has not been tried on a device.
- The boards are drawn in code; there is no hand-drawn art.
