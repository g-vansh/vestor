/* =============================================================================
 * Vestor — Faithful HUB75 LED-matrix simulator engine
 * -----------------------------------------------------------------------------
 * Two classes:
 *   LEDMatrix   — a software framebuffer + drawing API (pixels, primitives,
 *                 an authored 5x7 + 3x5 pixel font, and icon sprites). Stores
 *                 LINEAR light in a Float32Array so additive bloom is physical.
 *   LEDRenderer — paints an LEDMatrix onto a <canvas> the way a real panel
 *                 looks: gamma-corrected round emitters, dark inter-pixel gaps,
 *                 additive bloom, scanlines, 64px panel seams, vignette.
 *
 * No build step, no modules — loaded via a classic <script> tag so the whole
 * thing runs from file:// with zero tooling. Mirrors the on-Pi geometry:
 * one panel = 64x32; the full wall = 1024x32 (16 panels side by side, 201").
 * ========================================================================== */
'use strict';

/* ----- tiny color helpers (palette is authored in 0..255 for readability) -- */
function rgb(r, g, b) { return [r, g, b]; }
function scale(c, k) { return [c[0] * k, c[1] * k, c[2] * k]; }
function mix(a, b, t) {
  return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
}
function clamp(v, lo, hi) { return v < lo ? lo : v > hi ? hi : v; }

/* Vestor "Departure Board Noir" palette — sodium-amber primary, phosphor
 * accents, near-black substrate. Authored 0..255. */
const PAL = {
  amber:   rgb(255, 176, 0),
  amberDim:rgb(150, 100, 0),
  green:   rgb(57, 255, 20),
  greenDim:rgb(28, 120, 16),
  cyan:    rgb(0, 229, 255),
  cyanDim: rgb(0, 110, 130),
  magenta: rgb(255, 45, 149),
  white:   rgb(235, 240, 255),
  warm:    rgb(255, 214, 120),
  red:     rgb(255, 60, 50),
  redDim:  rgb(120, 24, 20),
  blue:    rgb(90, 150, 255),
  purple:  rgb(170, 120, 255),
  scarlet: rgb(255, 70, 60),     // BU brand scarlet (#CC0000-ish, LED-boosted)
  scarletDim: rgb(120, 28, 24),
  ink:     rgb(8, 8, 12),
};

/* ============================================================================
 * LEDMatrix — the software framebuffer
 * ========================================================================== */
class LEDMatrix {
  constructor(cols, rows) {
    this.cols = cols;
    this.rows = rows;
    // Linear light, 3 channels, 0..1 (can exceed 1 transiently for additive).
    this.buf = new Float32Array(cols * rows * 3);
  }

  clear(c) {
    if (!c) { this.buf.fill(0); return; }
    const r = c[0] / 255, g = c[1] / 255, b = c[2] / 255;
    for (let i = 0; i < this.buf.length; i += 3) {
      this.buf[i] = r; this.buf[i + 1] = g; this.buf[i + 2] = b;
    }
  }

  inBounds(x, y) { return x >= 0 && y >= 0 && x < this.cols && y < this.rows; }

  /* Overwrite a pixel. color = 0..255 triple, optional brightness 0..1. */
  set(x, y, c, a) {
    x |= 0; y |= 0;
    if (x < 0 || y < 0 || x >= this.cols || y >= this.rows) return;
    const k = (a === undefined ? 1 : a) / 255;
    const i = (y * this.cols + x) * 3;
    this.buf[i] = c[0] * k; this.buf[i + 1] = c[1] * k; this.buf[i + 2] = c[2] * k;
  }

  /* Additive — light adds up (overlapping glows brighten). */
  add(x, y, c, a) {
    x |= 0; y |= 0;
    if (x < 0 || y < 0 || x >= this.cols || y >= this.rows) return;
    const k = (a === undefined ? 1 : a) / 255;
    const i = (y * this.cols + x) * 3;
    this.buf[i] += c[0] * k; this.buf[i + 1] += c[1] * k; this.buf[i + 2] += c[2] * k;
  }

  /* Alpha blend toward color (a = 0..1). */
  blend(x, y, c, a) {
    x |= 0; y |= 0;
    if (x < 0 || y < 0 || x >= this.cols || y >= this.rows) return;
    const i = (y * this.cols + x) * 3;
    const r = c[0] / 255, g = c[1] / 255, b = c[2] / 255;
    this.buf[i]     = this.buf[i]     * (1 - a) + r * a;
    this.buf[i + 1] = this.buf[i + 1] * (1 - a) + g * a;
    this.buf[i + 2] = this.buf[i + 2] * (1 - a) + b * a;
  }

  get(x, y) {
    const i = (y * this.cols + x) * 3;
    return [this.buf[i] * 255, this.buf[i + 1] * 255, this.buf[i + 2] * 255];
  }

  /* ---- primitives ------------------------------------------------------- */
  hline(x0, x1, y, c, a) {
    if (x0 > x1) { const t = x0; x0 = x1; x1 = t; }
    for (let x = x0; x <= x1; x++) this.set(x, y, c, a);
  }
  vline(x, y0, y1, c, a) {
    if (y0 > y1) { const t = y0; y0 = y1; y1 = t; }
    for (let y = y0; y <= y1; y++) this.set(x, y, c, a);
  }
  rect(x, y, w, h, c, a) {
    this.hline(x, x + w - 1, y, c, a);
    this.hline(x, x + w - 1, y + h - 1, c, a);
    this.vline(x, y, y + h - 1, c, a);
    this.vline(x + w - 1, y, y + h - 1, c, a);
  }
  fillRect(x, y, w, h, c, a) {
    for (let yy = y; yy < y + h; yy++)
      for (let xx = x; xx < x + w; xx++) this.set(xx, yy, c, a);
  }
  line(x0, y0, x1, y1, c, a) {
    x0 |= 0; y0 |= 0; x1 |= 0; y1 |= 0;
    const dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
    const sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
    let err = dx + dy;
    for (;;) {
      this.set(x0, y0, c, a);
      if (x0 === x1 && y0 === y1) break;
      const e2 = 2 * err;
      if (e2 >= dy) { err += dy; x0 += sx; }
      if (e2 <= dx) { err += dx; y0 += sy; }
    }
  }
  circle(cx, cy, r, c, a) {
    let x = r, y = 0, err = 1 - r;
    while (x >= y) {
      this.set(cx + x, cy + y, c, a); this.set(cx + y, cy + x, c, a);
      this.set(cx - y, cy + x, c, a); this.set(cx - x, cy + y, c, a);
      this.set(cx - x, cy - y, c, a); this.set(cx - y, cy - x, c, a);
      this.set(cx + y, cy - x, c, a); this.set(cx + x, cy - y, c, a);
      y++;
      if (err < 0) err += 2 * y + 1;
      else { x--; err += 2 * (y - x) + 1; }
    }
  }
  fillCircle(cx, cy, r, c, a) {
    for (let y = -r; y <= r; y++)
      for (let x = -r; x <= r; x++)
        if (x * x + y * y <= r * r + r * 0.5) this.set(cx + x, cy + y, c, a);
  }

  /* ---- text ------------------------------------------------------------- */
  /* font: '5x7' (default) or '3x5'. Returns x advance past the string. */
  text(x, y, str, c, scale, font, a) {
    scale = scale || 1;
    const F = font === '3x5' ? FONT3x5 : FONT5x7;
    const gw = font === '3x5' ? 3 : 5;
    const gh = font === '3x5' ? 5 : 7;
    let cx = x;
    for (const ch of String(str).toUpperCase()) {
      const glyph = F[ch] || F['?'];
      for (let gy = 0; gy < gh; gy++) {
        const row = glyph[gy] || '';
        for (let gx = 0; gx < gw; gx++) {
          if (row[gx] === '1') {
            if (scale === 1) this.set(cx + gx, y + gy, c, a);
            else this.fillRect(cx + gx * scale, y + gy * scale, scale, scale, c, a);
          }
        }
      }
      cx += (gw + 1) * scale;
    }
    return cx;
  }
  measure(str, scale, font) {
    scale = scale || 1;
    const gw = font === '3x5' ? 3 : 5;
    return String(str).length * (gw + 1) * scale - scale;
  }
  /* right-aligned text ending at xRight */
  textRight(xRight, y, str, c, scale, font, a) {
    const w = this.measure(str, scale, font);
    return this.text(xRight - w, y, str, c, scale, font, a);
  }
  textCenter(xCenter, y, str, c, scale, font, a) {
    const w = this.measure(str, scale, font);
    return this.text(Math.round(xCenter - w / 2), y, str, c, scale, font, a);
  }

  /* ---- icon sprites (monochrome bitmaps, tinted to color) --------------- */
  icon(x, y, name, c, a) {
    const spr = ICONS[name];
    if (!spr) return;
    for (let gy = 0; gy < spr.length; gy++) {
      const row = spr[gy];
      for (let gx = 0; gx < row.length; gx++) {
        const ch = row[gx];
        if (ch === '1') this.set(x + gx, y + gy, c, a);
        else if (ch === '2') this.set(x + gx, y + gy, c, (a === undefined ? 255 : a) * 0.45);
      }
    }
  }
  iconSize(name) {
    const spr = ICONS[name];
    if (!spr) return [0, 0];
    return [spr[0].length, spr.length];
  }
}

/* ============================================================================
 * LEDRenderer — paints an LEDMatrix to a canvas with real-panel optics
 * ========================================================================== */
class LEDRenderer {
  constructor(canvas, matrix, opts) {
    opts = opts || {};
    this.canvas = canvas;
    this.m = matrix;
    this.pitch = opts.pitch || 12;        // screen px per LED
    this.dot = opts.dot !== undefined ? opts.dot : 0.78; // emitter diameter / pitch
    this.bloom = opts.bloom !== undefined ? opts.bloom : 0.55;
    this.gamma = opts.gamma || 2.2;
    this.scan = opts.scan !== undefined ? opts.scan : 0.10;  // scanline depth
    this.seam = opts.seam !== undefined ? opts.seam : true;  // 64px panel seams
    this.vignette = opts.vignette !== undefined ? opts.vignette : 0.22;
    this.ambient = opts.ambient || 6;     // unlit substrate brightness
    this.brightness = 1.0;                // global emission scale (console knob)

    const W = matrix.cols * this.pitch;
    const H = matrix.rows * this.pitch;
    canvas.width = W; canvas.height = H;
    this.W = W; this.H = H;
    this.ctx = canvas.getContext('2d');

    // lo-res buffer: one texel per LED
    this.lo = document.createElement('canvas');
    this.lo.width = matrix.cols; this.lo.height = matrix.rows;
    this.loCtx = this.lo.getContext('2d');
    this.loImg = this.loCtx.createImageData(matrix.cols, matrix.rows);

    // glow buffer (downsampled), painted back additively for bloom
    this.glow = document.createElement('canvas');
    this.glow.width = Math.max(1, Math.round(W / 3));
    this.glow.height = Math.max(1, Math.round(H / 3));
    this.glowCtx = this.glow.getContext('2d');

    this._buildDotMask();
    this._buildOverlay();
    this._buildGammaLUT();
  }

  _buildGammaLUT() {
    this.lut = new Uint8ClampedArray(1024);
    for (let i = 0; i < 1024; i++) {
      const v = i / 1023;
      this.lut[i] = Math.round(Math.pow(v, 1 / this.gamma) * 255);
    }
  }

  /* A tiled mask: one soft round dot per cell. destination-in carves the
   * upscaled square pixels into round emitters in ONE pass. */
  _buildDotMask() {
    const p = this.pitch;
    const tile = document.createElement('canvas');
    tile.width = p; tile.height = p;
    const t = tile.getContext('2d');
    const r = (p * this.dot) / 2;
    const cx = p / 2, cy = p / 2;
    const g = t.createRadialGradient(cx, cy, 0, cx, cy, r);
    g.addColorStop(0, 'rgba(255,255,255,1)');
    g.addColorStop(0.62, 'rgba(255,255,255,1)');
    g.addColorStop(0.86, 'rgba(255,255,255,0.85)');
    g.addColorStop(1, 'rgba(255,255,255,0)');
    t.fillStyle = g;
    t.beginPath(); t.arc(cx, cy, r, 0, Math.PI * 2); t.fill();
    this.mask = this.ctx.createPattern(tile, 'repeat');
  }

  /* Static overlay: scanlines + panel seams + vignette, drawn once. */
  _buildOverlay() {
    const o = document.createElement('canvas');
    o.width = this.W; o.height = this.H;
    const c = o.getContext('2d');

    // horizontal scanlines (every LED row, faint dark band on lower half)
    if (this.scan > 0) {
      c.fillStyle = `rgba(0,0,0,${this.scan})`;
      for (let y = 0; y < this.m.rows; y++) {
        c.fillRect(0, y * this.pitch + this.pitch * 0.62, this.W, this.pitch * 0.38);
      }
    }
    // panel seams every 64 columns (and 32 rows) — subtle darker hairline
    if (this.seam) {
      c.fillStyle = 'rgba(0,0,0,0.55)';
      for (let x = 64; x < this.m.cols; x += 64) c.fillRect(x * this.pitch - 1, 0, 2, this.H);
      for (let y = 32; y < this.m.rows; y += 32) c.fillRect(0, y * this.pitch - 1, this.W, 2);
    }
    // vignette
    if (this.vignette > 0) {
      const g = c.createRadialGradient(
        this.W / 2, this.H / 2, Math.min(this.W, this.H) * 0.2,
        this.W / 2, this.H / 2, Math.max(this.W, this.H) * 0.62);
      g.addColorStop(0, 'rgba(0,0,0,0)');
      g.addColorStop(1, `rgba(0,0,0,${this.vignette})`);
      c.fillStyle = g; c.fillRect(0, 0, this.W, this.H);
    }
    this.overlay = o;
  }

  render() {
    const m = this.m, lut = this.lut;
    const data = this.loImg.data;
    const buf = m.buf;
    const br = this.brightness;
    // framebuffer (linear 0..1) -> brightness scale -> gamma-corrected 8-bit
    for (let i = 0, j = 0; i < buf.length; i += 3, j += 4) {
      let rr = buf[i] * br, gg = buf[i + 1] * br, bb = buf[i + 2] * br;
      const r = rr < 0 ? 0 : rr > 1 ? 1 : rr;
      const g = gg < 0 ? 0 : gg > 1 ? 1 : gg;
      const b = bb < 0 ? 0 : bb > 1 ? 1 : bb;
      data[j]     = lut[(r * 1023) | 0];
      data[j + 1] = lut[(g * 1023) | 0];
      data[j + 2] = lut[(b * 1023) | 0];
      data[j + 3] = 255;
    }
    this.loCtx.putImageData(this.loImg, 0, 0);

    const ctx = this.ctx;
    ctx.globalCompositeOperation = 'source-over';
    // substrate (dark board with a faint warm tint)
    ctx.fillStyle = `rgb(${this.ambient},${this.ambient},${this.ambient + 1})`;
    ctx.fillRect(0, 0, this.W, this.H);

    // upscale the lo buffer to full res, hard pixels
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(this.lo, 0, 0, this.W, this.H);

    // carve square pixels into round dots (single pass)
    ctx.globalCompositeOperation = 'destination-in';
    ctx.fillStyle = this.mask;
    ctx.fillRect(0, 0, this.W, this.H);

    // re-lay the substrate *under* the dots
    ctx.globalCompositeOperation = 'destination-over';
    ctx.fillStyle = `rgb(${this.ambient},${this.ambient},${this.ambient + 1})`;
    ctx.fillRect(0, 0, this.W, this.H);

    // bloom: blur the dots and add them back
    if (this.bloom > 0) {
      this.glowCtx.clearRect(0, 0, this.glow.width, this.glow.height);
      this.glowCtx.imageSmoothingEnabled = true;
      this.glowCtx.drawImage(this.canvas, 0, 0, this.glow.width, this.glow.height);
      ctx.globalCompositeOperation = 'lighter';
      ctx.imageSmoothingEnabled = true;
      ctx.globalAlpha = this.bloom;
      ctx.drawImage(this.glow, 0, 0, this.W, this.H);
      // a second, wider, fainter pass for soft halo
      ctx.globalAlpha = this.bloom * 0.4;
      ctx.drawImage(this.glow, -this.pitch * 0.5, -this.pitch * 0.5,
        this.W + this.pitch, this.H + this.pitch);
      ctx.globalAlpha = 1;
    }

    // static overlay (scanlines/seams/vignette)
    ctx.globalCompositeOperation = 'source-over';
    ctx.drawImage(this.overlay, 0, 0);
  }
}

/* ============================================================================
 * Authored 5x7 pixel font  (uppercase, digits, punctuation, arrows, symbols)
 * Each glyph = 7 rows of 5 chars. '1' = lit.
 * ========================================================================== */
const FONT5x7 = {
  'A': ['01110','10001','10001','11111','10001','10001','10001'],
  'B': ['11110','10001','10001','11110','10001','10001','11110'],
  'C': ['01110','10001','10000','10000','10000','10001','01110'],
  'D': ['11110','10001','10001','10001','10001','10001','11110'],
  'E': ['11111','10000','10000','11110','10000','10000','11111'],
  'F': ['11111','10000','10000','11110','10000','10000','10000'],
  'G': ['01110','10001','10000','10111','10001','10001','01111'],
  'H': ['10001','10001','10001','11111','10001','10001','10001'],
  'I': ['01110','00100','00100','00100','00100','00100','01110'],
  'J': ['00111','00010','00010','00010','00010','10010','01100'],
  'K': ['10001','10010','10100','11000','10100','10010','10001'],
  'L': ['10000','10000','10000','10000','10000','10000','11111'],
  'M': ['10001','11011','10101','10101','10001','10001','10001'],
  'N': ['10001','10001','11001','10101','10011','10001','10001'],
  'O': ['01110','10001','10001','10001','10001','10001','01110'],
  'P': ['11110','10001','10001','11110','10000','10000','10000'],
  'Q': ['01110','10001','10001','10001','10101','10010','01101'],
  'R': ['11110','10001','10001','11110','10100','10010','10001'],
  'S': ['01111','10000','10000','01110','00001','00001','11110'],
  'T': ['11111','00100','00100','00100','00100','00100','00100'],
  'U': ['10001','10001','10001','10001','10001','10001','01110'],
  'V': ['10001','10001','10001','10001','10001','01010','00100'],
  'W': ['10001','10001','10001','10101','10101','11011','10001'],
  'X': ['10001','10001','01010','00100','01010','10001','10001'],
  'Y': ['10001','10001','01010','00100','00100','00100','00100'],
  'Z': ['11111','00001','00010','00100','01000','10000','11111'],
  '0': ['01110','10001','10011','10101','11001','10001','01110'],
  '1': ['00100','01100','00100','00100','00100','00100','01110'],
  '2': ['01110','10001','00001','00110','01000','10000','11111'],
  '3': ['11111','00010','00100','00010','00001','10001','01110'],
  '4': ['00010','00110','01010','10010','11111','00010','00010'],
  '5': ['11111','10000','11110','00001','00001','10001','01110'],
  '6': ['00110','01000','10000','11110','10001','10001','01110'],
  '7': ['11111','00001','00010','00100','01000','01000','01000'],
  '8': ['01110','10001','10001','01110','10001','10001','01110'],
  '9': ['01110','10001','10001','01111','00001','00010','01100'],
  ' ': ['00000','00000','00000','00000','00000','00000','00000'],
  ':': ['00000','00100','00100','00000','00100','00100','00000'],
  '.': ['00000','00000','00000','00000','00000','01100','01100'],
  ',': ['00000','00000','00000','00000','00000','00100','01000'],
  '-': ['00000','00000','00000','11111','00000','00000','00000'],
  '+': ['00000','00100','00100','11111','00100','00100','00000'],
  '/': ['00001','00010','00010','00100','01000','01000','10000'],
  '\\':['10000','01000','01000','00100','00010','00010','00001'],
  '_': ['00000','00000','00000','00000','00000','00000','11111'],
  '=': ['00000','00000','11111','00000','11111','00000','00000'],
  '°': ['01100','10010','10010','01100','00000','00000','00000'],
  '\'':['00100','00100','01000','00000','00000','00000','00000'],
  '"': ['01010','01010','01010','00000','00000','00000','00000'],
  '%': ['11001','11010','00010','00100','01000','01011','10011'],
  '!': ['00100','00100','00100','00100','00100','00000','00100'],
  '?': ['01110','10001','00001','00110','00100','00000','00100'],
  '(': ['00010','00100','01000','01000','01000','00100','00010'],
  ')': ['01000','00100','00010','00010','00010','00100','01000'],
  '#': ['01010','01010','11111','01010','11111','01010','01010'],
  '*': ['00000','10101','01110','11111','01110','10101','00000'],
  '<': ['00010','00100','01000','10000','01000','00100','00010'],
  '>': ['01000','00100','00010','00001','00010','00100','01000'],
  '^': ['00100','01010','10001','00000','00000','00000','00000'],
  '~': ['00000','00000','01000','10101','00010','00000','00000'],
  '@': ['01110','10001','10111','10101','10111','10000','01110'],
  '&': ['01100','10010','10100','01000','10101','10010','01101'],
  '|': ['00100','00100','00100','00100','00100','00100','00100'],
  '↑': ['00100','01110','10101','00100','00100','00100','00100'],
  '↓': ['00100','00100','00100','00100','10101','01110','00100'],
  '→': ['00000','00100','00010','11111','00010','00100','00000'],
  '←': ['00000','00100','01000','11111','01000','00100','00000'],
  '▲': ['00000','00100','00100','01110','01110','11111','00000'],
  '▼': ['00000','11111','01110','01110','00100','00100','00000'],
  '●': ['00000','01110','11111','11111','11111','01110','00000'],
};

/* Authored 3x5 tiny font for captions/labels (uppercase + digits) */
const FONT3x5 = {
  'A': ['010','101','111','101','101'], 'B': ['110','101','110','101','110'],
  'C': ['011','100','100','100','011'], 'D': ['110','101','101','101','110'],
  'E': ['111','100','110','100','111'], 'F': ['111','100','110','100','100'],
  'G': ['011','100','101','101','011'], 'H': ['101','101','111','101','101'],
  'I': ['111','010','010','010','111'], 'J': ['001','001','001','101','010'],
  'K': ['101','110','100','110','101'], 'L': ['100','100','100','100','111'],
  'M': ['101','111','111','101','101'], 'N': ['101','111','111','111','101'],
  'O': ['010','101','101','101','010'], 'P': ['110','101','110','100','100'],
  'Q': ['010','101','101','110','011'], 'R': ['110','101','110','101','101'],
  'S': ['011','100','010','001','110'], 'T': ['111','010','010','010','010'],
  'U': ['101','101','101','101','111'], 'V': ['101','101','101','101','010'],
  'W': ['101','101','111','111','101'], 'X': ['101','101','010','101','101'],
  'Y': ['101','101','010','010','010'], 'Z': ['111','001','010','100','111'],
  '0': ['111','101','101','101','111'], '1': ['010','110','010','010','111'],
  '2': ['110','001','010','100','111'], '3': ['110','001','010','001','110'],
  '4': ['101','101','111','001','001'], '5': ['111','100','110','001','110'],
  '6': ['011','100','111','101','111'], '7': ['111','001','010','010','010'],
  '8': ['111','101','111','101','111'], '9': ['111','101','111','001','110'],
  ' ': ['000','000','000','000','000'], ':': ['000','010','000','010','000'],
  '.': ['000','000','000','000','010'], '-': ['000','000','111','000','000'],
  '/': ['001','001','010','100','100'], '°': ['110','110','000','000','000'],
  '+': ['000','010','111','010','000'], '%': ['101','001','010','100','101'],
  '?': ['110','001','010','000','010'], '!': ['010','010','010','000','010'],
  '↑': ['010','111','010','010','000'], '↓': ['000','010','010','111','010'],
  '→': ['000','010','111','010','000'], '←': ['000','010','111','010','000'],
  '#': ['101','111','101','111','101'], '*': ['000','101','010','101','000'],
};

/* ============================================================================
 * Icon sprites — monochrome bitmaps tinted to a color.
 * '1' = full, '2' = dim (anti-alias hint), '0'/' ' = off.
 * ========================================================================== */
const ICONS = {
  // 7x7 jetliner (top-down), nose right
  plane: [
    '0001000',
    '0001000',
    '0001100',
    '1111111',
    '0001100',
    '0001000',
    '0011000',
  ],
  // small plane 5x5
  planeSm: [
    '00100',
    '00110',
    '11111',
    '00110',
    '00100',
  ],
  // bike 11x7
  bike: [
    '00000000000',
    '01100011000',
    '10010100100',
    '10001001010',
    '10010100100',
    '01100011000',
    '00000000000',
  ],
  // lightning bolt (ebike) 5x7
  bolt: [
    '00110',
    '00100',
    '01100',
    '11111',
    '00110',
    '00100',
    '01000',
  ],
  // bus 11x7
  bus: [
    '01111111110',
    '10000000001',
    '10110110101',
    '10110110101',
    '10000000001',
    '01101101100',
    '00100100100',
  ],
  // sun 7x7
  sun: [
    '0001000',
    '1001001',
    '0011100',
    '0111110',
    '0011100',
    '1001001',
    '0001000',
  ],
  // moon 7x7
  moon: [
    '0011100',
    '0110000',
    '1100000',
    '1100000',
    '1100000',
    '0110001',
    '0011110',
  ],
  // cloud 9x6
  cloud: [
    '000111000',
    '011111110',
    '111111111',
    '111111111',
    '011111110',
    '000000000',
  ],
  // rain 9x7
  rain: [
    '000111000',
    '011111110',
    '111111111',
    '011111110',
    '010101010',
    '101010100',
    '010101010',
  ],
  // snow 7x7
  snow: [
    '0010100',
    '1011101',
    '0111110',
    '0010100',
    '0111110',
    '1011101',
    '0010100',
  ],
  // person 3x7 (dock waiting figure)
  person: [
    '010',
    '111',
    '010',
    '010',
    '010',
    '101',
    '101',
  ],
  // dock / parking 'P' marker 5x7 handled by font; small pin:
  pin: [
    '01110',
    '10001',
    '10101',
    '10001',
    '01110',
    '00100',
    '00100',
  ],
  // ISS / satellite 9x5
  sat: [
    '101000101',
    '111010111',
    '001111100',
    '111010111',
    '101000101',
  ],
  // wind gust 9x5
  wind: [
    '011110000',
    '100001000',
    '000011110',
    '100001000',
    '011110000',
  ],
  // heart/up trend arrow already in font; chevrons:
  chevR: ['10','11','01','11','10'],
  chevL: ['01','11','10','11','01'],
};

/* expose globals */
window.LEDMatrix = LEDMatrix;
window.LEDRenderer = LEDRenderer;
window.PAL = PAL;
window.rgb = rgb; window.mix = mix; window.scale = scale; window.clamp = clamp;
