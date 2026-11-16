package app.shortcam.engine

/**
 * Conventions shared by the stage shaders:
 *  - The chain always runs in sensor orientation (landscape), so scan lines are rows.
 *  - uGrid is the fault grid in columns and lines. Faults quantise to it rather than to
 *    render pixels, so the viewfinder and a full-resolution still show the same fault.
 *  - Raster coordinates `p` have y running down from the first line read out.
 *  - Every random choice hashes uTime and grid position only, so re-rendering a frame
 *    with the same uTime reproduces it exactly.
 */
internal object Shaders {

    /** Pass-through vertex shader. vUv has y pointing up. */
    const val QUAD_VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    /** Vertex shader with a texture-coordinate transform (ingest, present, still readout). */
    const val TRANSFORM_VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform mat4 uTexMatrix;
out vec2 vUv;
void main() {
    vUv = (uTexMatrix * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    const val INGEST_FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTex;
in vec2 vUv;
out vec4 outColor;
void main() {
    outColor = vec4(texture(uTex, vUv).rgb, 1.0);
}
"""

    const val COPY_FRAGMENT = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
in vec2 vUv;
out vec4 outColor;
void main() {
    outColor = vec4(texture(uTex, vUv).rgb, 1.0);
}
"""

    /** What the screen shows while the camera is crashed: the last frame, read out all wrong. */
    const val GARBLE_FRAGMENT = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uTex;
uniform float uTime;
in vec2 vUv;
out vec4 outColor;

float hash21(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    float slot = floor(uTime * 14.0);
    vec2 p = vUv;
    vec2 cell = floor(p * vec2(8.0, 48.0));
    float r = hash21(cell + slot);
    p.x = fract(p.x + (hash21(vec2(cell.y, slot)) - 0.5) * 0.8);
    if (r < 0.3) p = fract(p * vec2(4.0, 1.0) + r * 7.0);
    ivec3 v = ivec3(texture(uTex, p).rgb * 255.0);
    v ^= ivec3(int(hash21(vec2(cell.y, slot + 1.0)) * 255.0));
    vec3 c = vec3(v & 255) / 255.0;
    if (hash21(vec2(floor(vUv.y * 200.0), slot)) < 0.15) c = vec3(step(0.5, hash21(cell + slot + 3.0)));
    outColor = vec4(c, 1.0);
}
"""

    private const val STAGE_HEADER ="""#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uTex;
uniform sampler2D uPrev;
uniform vec2 uGrid;
uniform float uTime;
in vec2 vUv;
out vec4 outColor;

float hash21(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// How hard a fault bites right now. A weak contact flickers: it fires only some of the
// time, more often as the amount rises, and holds steady from a little under half way.
// The strength it fires at rises with the amount over the whole range, so a pot on the
// wire is felt from one end of its travel to the other.
// `rate` is how many times a second the contact can make or break.
float bite(float amount, float seed, float rate) {
    if (amount <= 0.0) return 0.0;
    float duty = clamp(amount * 2.2, 0.0, 1.0);
    return hash21(vec2(floor(uTime * rate), seed)) < duty ? mix(0.2, 1.0, amount) : 0.0;
}

vec2 raster() { return vec2(vUv.x, 1.0 - vUv.y); }
vec3 tap(vec2 p) { return texture(uTex, vec2(p.x, 1.0 - p.y)).rgb; }

vec3 toYuv(vec3 c) {
    return vec3(
        dot(c, vec3(0.299, 0.587, 0.114)),
        dot(c, vec3(-0.169, -0.331, 0.5)),
        dot(c, vec3(0.5, -0.419, -0.081)));
}
vec3 toRgb(vec3 y) {
    return vec3(y.x + 1.402 * y.z, y.x - 0.344 * y.y - 0.714 * y.z, y.x + 1.772 * y.y);
}
"""

    /** Stage 1: lines and columns clocked off the sensor. */
    const val SENSOR = STAGE_HEADER + """
uniform float uLineSkip;
uniform float uColumnShift;
uniform float uSmear;
uniform float uStuck;
uniform float uWobble;

void main() {
    vec2 p = raster();
    float line = floor(p.y * uGrid.y);

    // The line counter stalls (lines repeat) or jumps (lines are skipped) in bands.
    float a = bite(uLineSkip, 11.0, 9.0);
    if (a > 0.0) {
        float slot = floor(uTime * 9.0);
        float band = floor(line / 24.0);
        float r = hash21(vec2(band, slot + 3.0));
        float moved = line;
        if (r < a * 0.55) {
            float hold = 2.0 + floor(hash21(vec2(band, slot + 5.0)) * 14.0 * a);
            moved = floor(line / hold) * hold;
        } else if (r > 1.0 - a * 0.3) {
            float jump = floor((hash21(vec2(band, slot + 7.0)) - 0.5) * 240.0 * a);
            moved = band * 24.0 + (line - band * 24.0) * 2.0 + jump;
        }
        if (moved != line) p.y = (clamp(moved, 0.0, uGrid.y - 1.0) + 0.5) / uGrid.y;
    }

    // The column clock slips, so bands of lines start late or early.
    float c = bite(uColumnShift, 23.0, 7.0);
    if (c > 0.0) {
        float slot = floor(uTime * 7.0);
        float band = floor(line / 16.0);
        if (hash21(vec2(band, slot + 13.0)) < c * 0.6) {
            float shift = floor((hash21(vec2(band, slot + 17.0)) - 0.5) * 160.0 * c);
            p.x = fract(p.x + shift / uGrid.x);
        }
    }

    // The line clock wavers: each line starts a little off the one before, and the
    // whole picture sways like jelly.
    float w = bite(uWobble, 37.0, 5.0);
    if (w > 0.0) {
        p.x = fract(p.x + w * 0.06 * sin(p.y * 14.0 + uTime * 6.0) + w * 0.02 * sin(p.y * 61.0 - uTime * 11.0));
    }

    vec3 col = tap(p);

    // CCD smear: charge from a bright light leaks down its whole column.
    float s = bite(uSmear, 31.0, 6.0);
    if (s > 0.0) {
        float column = floor(p.x * uGrid.x * 0.5);
        float peak = 0.0;
        for (int i = 0; i < 24; i++) {
            float y = (float(i) + hash21(vec2(float(i), column))) / 24.0;
            float l = dot(tap(vec2(p.x, y)), vec3(0.299, 0.587, 0.114));
            peak = max(peak, l - 0.93);
        }
        col += vec3(1.0, 0.93, 1.0) * max(peak, 0.0) * 12.0 * s;
    }

    // Cells of the sensor fail for good: single pixels stuck at full charge or dead, and
    // whole columns stuck from the bad cell downwards. They sit where they are on the
    // sensor, whatever the readout is doing, and more of them fail the harder it is driven.
    if (uStuck > 0.0) {
        vec2 cell = floor(raster() * uGrid);
        float pix = hash21(cell + 193.0);
        if (pix < uStuck * 0.012) {
            col = pix < uStuck * 0.003 ? vec3(0.0) : step(0.35, fract(vec3(pix * 9770.0, pix * 19310.0, pix * 33010.0)));
        }
        float colr = hash21(vec2(cell.x, 191.0));
        if (colr < uStuck * 0.03 && raster().y > hash21(vec2(cell.x, 197.0)) * 0.8) {
            vec3 tint = step(0.5, fract(vec3(colr * 811.0, colr * 1723.0, colr * 2917.0)));
            col = colr < uStuck * 0.008 ? vec3(0.0) : max(col, tint * 0.9 + 0.1);
        }
    }

    outColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
"""

    /** Stage 2: analogue levels turned into bits. */
    const val ADC = STAGE_HEADER + """
uniform float uBaseBits;
uniform float uBitCrush;
uniform float uBitSwap;
uniform float uFold;
uniform float uChannelDrop;

void main() {
    vec2 p = raster();
    float line = floor(p.y * uGrid.y);
    vec3 c = tap(p);

    // Input overdriven past full scale: the converter wraps and tones fold back inverted.
    float f = bite(uFold, 67.0, 5.0);
    if (f > 0.0) {
        vec3 t = c * (1.0 + f * 3.0);
        c = 1.0 - abs(mod(t, 2.0) - 1.0);
    }

    // One colour channel's input comes adrift: in bands of lines that channel reads
    // nothing at all, or full scale.
    float cd = bite(uChannelDrop, 59.0, 4.0);
    if (cd > 0.0) {
        float slot = floor(uTime * 4.0);
        float band = floor(line / 60.0);
        if (hash21(vec2(band, slot + 21.0)) < 0.35 + 0.65 * cd) {
            int ch = int(hash21(vec2(slot, 23.0)) * 3.0);
            float level = hash21(vec2(band, slot + 25.0)) < 0.5 ? 0.0 : 1.0;
            if (ch == 0) c.r = level; else if (ch == 1) c.g = level; else c.b = level;
        }
    }

    ivec3 v = ivec3(clamp(c, 0.0, 1.0) * 255.0 + 0.5);

    // A cheap converter resolves fewer bits even when healthy. Low data lines tied to
    // ground lose more.
    int drop = 8 - int(clamp(uBaseBits, 1.0, 8.0));
    float a = bite(uBitCrush, 41.0, 8.0);
    if (a > 0.0) drop = max(drop, int(mix(3.0, 7.0, a) + 0.5));
    int top = 255;
    if (drop > 0) {
        v = (v >> drop) << drop;
        top = (255 >> drop) << drop;
    }

    // Two data lines bridged or crossed, in bands of lines.
    float b = bite(uBitSwap, 53.0, 6.0);
    if (b > 0.0) {
        float slot = floor(uTime * 6.0);
        float band = floor(line / 40.0);
        if (hash21(vec2(band, slot + 2.0)) < b) {
            int mode = int(hash21(vec2(band, slot + 9.0)) * 3.0);
            int lo = mode == 0 ? 5 : (mode == 1 ? 4 : 6);
            ivec3 x = ((v >> 7) ^ (v >> lo)) & 1;
            v ^= (x << 7) | (x << lo);
            top = 255;
        }
    }

    outColor = vec4(vec3(v) / float(top), 1.0);
}
"""

    /** Stage 3: RAM holding the current frame. uPrev is this stage's previous output. */
    const val MEMORY = STAGE_HEADER + """
uniform float uAddrShort;
uniform float uStale;
uniform float uTrails;
uniform float uFeedback;
uniform float uMirror;

void main() {
    vec2 p = raster();

    // Address lines shorted low: parts of the frame read the wrong cells and tile.
    float a = bite(uAddrShort, 71.0, 5.0);
    if (a > 0.0) {
        vec2 cell = p * uGrid;
        ivec2 addr = ivec2(cell);
        int level = int(ceil(a * 4.0));
        addr.x &= ~(1 << 6);
        if (level >= 2) addr.y &= ~(1 << 5);
        if (level >= 3) addr.x &= ~(1 << 8);
        if (level >= 4) addr.y &= ~(1 << 7);
        p = (vec2(addr) + fract(cell)) / uGrid;
    }

    // An address line reads inverted: half of the memory comes back in reverse, so the
    // picture folds onto itself down the middle, and across it too when pushed hard.
    float mr = bite(uMirror, 79.0, 3.0);
    if (mr > 0.0) {
        if (p.x > 0.5) p.x = 1.0 - p.x;
        if (mr > 0.7 && p.y > 0.5) p.y = 1.0 - p.y;
    }

    vec3 c = tap(p);
    vec3 m = texture(uPrev, vUv).rgb;

    // Write enable drops out for some cells, which keep whatever they last held.
    float s = bite(uStale, 83.0, 3.0);
    if (s > 0.0) {
        vec2 block = floor(raster() * vec2(16.0, 12.0));
        float slot = floor(uTime * 1.5);
        if (hash21(block + slot * 17.0) < s * 0.6) c = m;
    }

    // Old contents bleed into the new frame and decay slowly.
    float t = bite(uTrails, 97.0, 4.0);
    if (t > 0.0) c = max(c, m - mix(0.2, 0.004, uTrails));

    // The read pointer runs ahead of the write pointer, so the frame is read back into
    // itself a little larger each pass. The dark parts of the new picture let the old
    // one through, and it tunnels outwards, its colours creeping round as it goes.
    float fb = bite(uFeedback, 89.0, 3.0);
    if (fb > 0.0) {
        vec2 q = (vUv - 0.5) * (1.0 - 0.05 * fb) + 0.5 + vec2(0.004 * sin(uTime * 0.6), 0.0);
        vec3 old = texture(uPrev, q).rgb;
        old = mix(old, old.brg, 0.06);
        float dark = 1.0 - smoothstep(0.25, 0.65, dot(c, vec3(0.299, 0.587, 0.114)));
        c = mix(c, old, clamp(dark + 0.25, 0.0, 1.0) * (0.55 + 0.42 * fb));
    }

    outColor = vec4(c, 1.0);
}
"""

    /** Stage 4: luma and chroma combined. */
    const val ENCODER = STAGE_HEADER + """
uniform float uBaseSoft;
uniform float uChromaSwap;
uniform float uChromaDelay;
uniform float uChromaDrift;
uniform float uHueSpin;
uniform float uColorKill;

void main() {
    vec2 p = raster();
    float line = floor(p.y * uGrid.y);
    float y = toYuv(tap(p)).x;

    // Chroma arrives late: colour sits to the side of the luma it belongs to.
    vec2 cp = p;
    float d = bite(uChromaDelay, 101.0, 6.0);
    if (d > 0.0) {
        cp.x -= d * 0.12 + (hash21(vec2(line, floor(uTime * 30.0))) - 0.5) * 0.01 * d;
    }
    vec2 uv = toYuv(tap(cp)).yz;
    if (d > 0.0) {
        vec2 o = vec2(4.0 / uGrid.x, 0.0);
        uv = (uv + toYuv(tap(cp - o)).yz + toYuv(tap(cp + o)).yz) / 3.0;
    }

    // An analogue encoder has little chroma bandwidth: colour smears sideways and
    // trails behind edges even when nothing is bent.
    if (uBaseSoft > 0.0) {
        float reach = uBaseSoft * 0.02;
        uv = (uv * 2.0
            + toYuv(tap(cp - vec2(reach, 0.0))).yz * 2.0
            + toYuv(tap(cp - vec2(reach * 2.0, 0.0))).yz
            + toYuv(tap(cp + vec2(reach, 0.0))).yz) / 6.0;
    }

    // The two colour-difference signals crossed, in bands of lines.
    float s = bite(uChromaSwap, 113.0, 5.0);
    if (s > 0.0) {
        float slot = floor(uTime * 5.0);
        float band = floor(line / 32.0);
        if (hash21(vec2(band, slot + 4.0)) < s) {
            uv = uv.yx * (hash21(vec2(band, slot + 6.0)) < 0.5 ? 1.0 : -1.0);
        }
    }

    // Colour phase reference lost: hue rotates over time and down the frame.
    float r = bite(uChromaDrift, 127.0, 4.0);
    if (r > 0.0) {
        float angle = r * (3.1416 * sin(uTime * 0.9) + p.y * 18.0 * r);
        float cs = cos(angle);
        float sn = sin(angle);
        uv = vec2(uv.x * cs - uv.y * sn, uv.x * sn + uv.y * cs) * (1.0 + r * 0.6);
    }

    // The colour oscillator runs free of the picture: every hue in the frame turns
    // round the colour wheel together, faster the further off frequency it is.
    float h = bite(uHueSpin, 109.0, 4.0);
    if (h > 0.0) {
        float angle = uTime * mix(0.6, 7.0, uHueSpin);
        float cs = cos(angle);
        float sn = sin(angle);
        uv = vec2(uv.x * cs - uv.y * sn, uv.x * sn + uv.y * cs) * (1.0 + h * 0.5);
    }

    // The colour killer trips: it decides there is no colour signal and cuts it, in
    // bands of lines that come and go.
    float ck = bite(uColorKill, 103.0, 6.0);
    if (ck > 0.0 && hash21(vec2(floor(line / 20.0), floor(uTime * 6.0) + 31.0)) < 0.3 + 0.7 * ck) {
        uv = vec2(0.0);
    }

    outColor = vec4(clamp(toRgb(vec3(y, uv)), 0.0, 1.0), 1.0);
}
"""

    /** Stage 5: timing for the whole chain. uPrev is this stage's previous output. */
    const val SYNC = STAGE_HEADER + """
uniform float uTear;
uniform float uRoll;
uniform float uStarve;
uniform float uTracking;
uniform float uComb;
uniform float uDropout;

void main() {
    vec2 p = raster();
    float line = floor(p.y * uGrid.y);

    // Clock pulled low: whole frames never arrive and the last one hangs, with
    // lines dropping out of it.
    float st = bite(uStarve, 151.0, 10.0);
    if (st > 0.0 && hash21(vec2(floor(uTime * 10.0), 3.0)) < st * 0.8) {
        vec3 held = texture(uPrev, vUv).rgb;
        if (hash21(vec2(line, floor(uTime * 30.0))) < st * 0.06) held *= 0.25;
        outColor = vec4(held, 1.0);
        return;
    }

    // Horizontal sync slips: everything below a tear point starts at the wrong column.
    float t = bite(uTear, 131.0, 8.0);
    if (t > 0.0) {
        float slot = floor(uTime * 8.0);
        float offset = 0.0;
        for (int i = 0; i < 3; i++) {
            float fi = float(i);
            if (hash21(vec2(slot, 30.0 + fi)) < t && p.y > hash21(vec2(slot, 20.0 + fi))) {
                offset += (hash21(vec2(slot, 40.0 + fi)) - 0.5) * 0.5 * t;
            }
        }
        if (offset != 0.0) {
            offset += (hash21(vec2(line, floor(uTime * 30.0))) - 0.5) * 0.012 * t;
            p.x = fract(p.x + offset);
        }
    }

    // Vertical sync lost: the frame rolls, with the blanking bar visible at the seam.
    bool blank = false;
    float r = bite(uRoll, 139.0, 2.0);
    if (r > 0.0) {
        float phase = fract(uTime * mix(0.3, 4.0, uRoll));
        blank = abs(p.y - (1.0 - phase)) < 0.018;
        p.y = fract(p.y + phase);
    }

    // Tracking lost: a band of torn, snowy lines wanders up and down the frame.
    float snow = 0.0;
    float k = bite(uTracking, 157.0, 5.0);
    if (k > 0.0) {
        float centre = fract(uTime * 0.13 + 0.3 * sin(uTime * 0.7));
        float reach = 0.04 + 0.12 * k;
        float d = abs(raster().y - centre);
        if (d < reach) {
            snow = (1.0 - d / reach) * k;
            p.x = fract(p.x + (hash21(vec2(line, floor(uTime * 30.0) + 5.0)) - 0.5) * 0.25 * snow);
        }
    }

    vec3 c = blank ? vec3(0.02) : tap(p);
    if (snow > 0.0) {
        vec2 grain = vec2(floor(raster().x * uGrid.x * 0.5), line + floor(uTime * 30.0) * 13.0);
        if (hash21(grain) < snow * 0.5) c = vec3(0.55 + 0.45 * hash21(grain + 7.0));
    }

    // The two fields of the frame slip apart: every other line is still the last
    // frame's, and sits a little to one side, so edges comb.
    float cb = bite(uComb, 167.0, 6.0);
    float tick = floor(uTime * 30.0);
    if (cb > 0.0 && mod(line + tick, 2.0) < 1.0) {
        float slip = cb * 0.035 * (0.6 + 0.4 * sin(uTime * 2.3));
        c = texture(uPrev, vec2(fract(vUv.x + slip), vUv.y)).rgb;
    }

    // Tape dropouts: where oxide has flaked off there is no signal, and the line shows
    // a white streak that fades as the signal comes back. The head switch at the foot
    // of the frame tears the last few lines.
    float dp = bite(uDropout, 179.0, 12.0);
    if (dp > 0.0) {
        float x = raster().x - hash21(vec2(line, tick + 12.0));
        float len = 0.04 + 0.3 * hash21(vec2(line, tick + 13.0));
        if (hash21(vec2(line, tick + 11.0)) < dp * 0.05 && x > 0.0 && x < len) {
            c = mix(vec3(0.97), c, x / len * 0.7);
        }
        float foot = raster().y - (1.0 - 0.02 - 0.04 * dp);
        if (foot > 0.0) {
            float n = hash21(vec2(line, tick + 14.0));
            c = tap(vec2(fract(p.x + 0.06 + 0.1 * n), p.y));
            if (hash21(vec2(floor(raster().x * uGrid.x * 0.25), line + tick * 7.0)) < 0.25) c = vec3(0.6 + 0.4 * n);
        }
    }
    outColor = vec4(c, 1.0);
}
"""

    /**
     * Stage 6: live approximation of JPEG damage. Blocks are 8 grid cells square, which
     * is one 16-pixel MCU at capture resolution. A hit in the stream damages every block
     * after it in raster order, as a corrupted byte does in a real file.
     */
    const val COMPRESSOR = STAGE_HEADER + """
uniform float uBlockCorrupt;
uniform float uQuant;

void main() {
    vec2 p = raster();
    vec2 blocks = uGrid / 8.0;
    vec2 bi = floor(p * blocks);
    float total = blocks.x * blocks.y;
    float order = (bi.y * blocks.x + bi.x) / total;

    vec3 dc = vec3(0.0);
    float shift = 0.0;
    bool garbage = false;
    float slot = floor(uTime * 4.0);
    float a = bite(uBlockCorrupt, 163.0, 4.0);
    if (a > 0.0) {
        for (int i = 0; i < 4; i++) {
            float fi = float(i);
            if (fi / 4.0 >= a) break;
            float at = hash21(vec2(slot, 50.0 + fi));
            if (order >= at) {
                vec3 h = vec3(
                    hash21(vec2(slot, 60.0 + fi)),
                    hash21(vec2(slot, 70.0 + fi)),
                    hash21(vec2(slot, 80.0 + fi)));
                dc += (h - 0.5) * vec3(0.3, 0.28, 0.28) * a;
                shift += floor((hash21(vec2(slot, 90.0 + fi)) - 0.5) * 24.0);
                if (order - at < 14.0 / total) garbage = true;
            }
        }
    }

    vec2 q = vec2(fract(p.x + shift * 8.0 / uGrid.x), p.y);
    vec3 c = tap(q);

    // Quantisation tables wrecked: chroma collapses to one value per block, then
    // luma detail goes coarse.
    float qn = bite(uQuant, 173.0, 3.0);
    if (qn > 0.0) {
        vec2 centre = (floor(q * blocks) + 0.5) / blocks;
        vec2 o = 2.5 / uGrid;
        vec3 avg = (tap(centre) + tap(centre + o) + tap(centre - o)
            + tap(centre + vec2(o.x, -o.y)) + tap(centre + vec2(-o.x, o.y))) / 5.0;
        vec3 yc = toYuv(c);
        vec3 ya = toYuv(avg);
        float coarse = mix(0.03, 0.5, qn);
        yc.yz = mix(yc.yz, ya.yz, min(qn * 1.5, 1.0));
        yc.x = ya.x + floor((yc.x - ya.x) / coarse + 0.5) * coarse;
        c = toRgb(yc);
    }

    // The blocks decoded from the damaged bytes themselves are noise.
    if (garbage) {
        vec2 f = fract(q * blocks);
        float n = hash21(bi + slot);
        float pattern = sign(cos(f.x * 3.1416 * floor(1.0 + n * 7.0))
            * cos(f.y * 3.1416 * floor(1.0 + fract(n * 7.3) * 7.0)));
        c = (0.5 + 0.45 * pattern) * vec3(n, fract(n * 3.7), fract(n * 9.1)) * 1.6;
    }

    outColor = vec4(clamp(toRgb(toYuv(c) + dc), 0.0, 1.0), 1.0);
}
"""
}
