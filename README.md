# Short: Circuit-Bent Camera

> **Disclaimer:** this app is vibecoded. The code was written by an AI (Claude). My input was creative direction and little else. It has only been tried on one Android tablet, so expect rough edges.

Short is an Android camera app for making glitch photos, GIFs and video.

It has no filters and no effects menu. You pick up an old camera, open its back, and join points on its circuit board with wires. Each wire breaks something inside the camera, and the picture breaks with it. What a wire does is not written anywhere on the board, so you find out by trying.

## The cameras

Three made-up cameras from a made-up maker, CSE (Cold Solder Electric Co.). Each has its own board, its own look and its own ways of going wrong.

| Camera | What it is | Character |
|---|---|---|
| **CSE Optika DX-210** | 2-megapixel digicam | Clean and sharp until bent. The most stable. 3x zoom. |
| **CSE Sonora V8 Hi-Band** | Tape camcorder | Soft colour, tape faults, tracking trouble. 8x zoom. |
| **CSE Pix Pal** | Toy camera | Tiny, blocky picture with few colours. Crashes easily. |

## How to use it

1. **Pick a camera** from the shelf.
2. **Shoot** from the front. It has the viewfinder, the shutter, a record button for video, a zoom rocker, a switch between the rear and selfie cameras, and the shutter mode.
3. **Tap OPEN** to turn the camera over and get at its board.
4. **Drag from one pad to another** to solder a wire between them. Watch the small screen on the board to see what it did.
5. **Tap CLOSE** to go back to the front, or shoot from the strip under the board.

### On the board

| To do this | Do this |
|---|---|
| Add a wire | Drag from one round pad to another |
| Remove a wire | Tap it |
| Fit a pot (strength knob) to a wire | Hold the wire for half a second |
| Turn the pot | Drag sideways on it |
| Swap the pot for an on/off switch | Hold the wire again |
| Flick the switch | Tap it |
| Go back to a bare wire | Hold the wire a third time |
| Bend with a finger | Press and hold a copper contact pad (K1, K2, ...) |

Things worth knowing:

- **Almost every pair of pads does something.** Some pairs are strong, some are faint.
- **Some pairs crash the camera.** It freezes, garbles, goes dark and restarts. Your wires stay where they were.
- **Too many wires overload it.** The more you bend, the more often it falls over.
- **Bends drift.** The same wires never look quite the same twice.

### Notes and mods

- **NOTES** is a notebook for each camera. It fills in by itself: every time you find a pair of pads that does something, it writes down what happened.
- **MODS** saves the wires on the board under a name, so you can load them again later. It can also pull all wires at once.
- **Every photo remembers its wires.** In MODS, "load patch from a photo" puts the board back the way it was when that photo was taken.

### Shutter modes

Tap the mode label on the front to step through them.

| Mode | What the shutter does |
|---|---|
| **STILL** | One photo |
| **GIF** | Up to 3 seconds as a looping GIF. Press again to stop early. |
| **DOUBLE** | Two presses, one photo: the second shot is laid over the first |
| **LONG** | About 2 seconds gathered into one photo, so movement blurs and lights leave trails |
| **LAPSE** | One frame a second until you press again, saved as a GIF |

The red button records video with sound, in any mode.

### Where things are saved

Photos and GIFs go to `Pictures/Short` and videos to `Movies/Short`, so they show up in your normal gallery. The small print in the corner of the camera opens the last one.

On the shelf there is a setting to save a clean, unbent copy beside each photo.

## Installing

There is no store release. To build it yourself you need Android Studio (or the Android SDK and a JDK) and a device running Android 12 or newer.

```bash
./gradlew :app:assembleDebug
```

The app is then at `app/build/outputs/apk/debug/app-debug.apk`.

## More detail

How it works inside, the full list of faults, and how to add a camera of your own are in [docs/TECHNICAL.md](docs/TECHNICAL.md).
