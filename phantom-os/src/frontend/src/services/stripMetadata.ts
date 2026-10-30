/**
 * Метадані геть з УСЬОГО, що їде людям: місце (GPS), модель телефона, час.
 *
 * Перекодування полотном EXIF і так губить, але мале фото, GIF, HEIC, відео
 * чи «файл» їхали байт-у-байт — разом із координатами дому.
 *
 *  - JPEG: лишаємо лише те, що потрібне для пікселів (DQT/SOF/DHT/SOS…),
 *    ICC (колір) і Adobe APP14 (CMYK); хвіст після EOI (Samsung, MPF) — геть;
 *  - PNG: без tEXt/zTXt/iTXt/eXIf/tIME; WebP: без EXIF/XMP;
 *  - поворот: якщо EXIF казав «повернути», кладемо новий EXIF рівно з одним
 *    полем Orientation — фото стоїть так само, як до чистки;
 *  - MP4/MOV/HEIC/AVIF: udta/meta/XMP-uuid стають `free` з нулями (розміри
 *    й зсуви ті самі — файл грає); Exif/XMP-елементи HEIF — нулі; час у
 *    mvhd/tkhd/mdhd — нуль;
 *  - незнайомий чи битий формат — як є (не відмова надсилання).
 */

const XMP_UUID = [0xbe, 0x7a, 0xcf, 0xcb, 0x97, 0xa9, 0x42, 0xe8, 0x9c, 0x71, 0x99, 0x94, 0x91, 0xe3, 0xaf, 0xac];

const ascii = (b: Uint8Array, at: number, s: string) => {
  if (at + s.length > b.length) return false;
  for (let i = 0; i < s.length; i += 1) if (b[at + i] !== s.charCodeAt(i)) return false;
  return true;
};
const u16 = (b: Uint8Array, at: number) => (b[at] << 8) | b[at + 1];
const u32 = (b: Uint8Array, at: number) => ((b[at] << 24) >>> 0) + ((b[at + 1] << 16) | (b[at + 2] << 8) | b[at + 3]);
const le32 = (b: Uint8Array, at: number) => (b[at] | (b[at + 1] << 8) | (b[at + 2] << 16)) + ((b[at + 3] << 24) >>> 0);
const putLe32 = (b: Uint8Array, at: number, v: number) => {
  b[at] = v & 0xff;
  b[at + 1] = (v >>> 8) & 0xff;
  b[at + 2] = (v >>> 16) & 0xff;
  b[at + 3] = (v >>> 24) & 0xff;
};
const putBe32 = (b: Uint8Array, at: number, v: number) => {
  b[at] = (v >>> 24) & 0xff;
  b[at + 1] = (v >>> 16) & 0xff;
  b[at + 2] = (v >>> 8) & 0xff;
  b[at + 3] = v & 0xff;
};

const concat = (parts: Uint8Array[]) => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
};

/** Orientation з TIFF (IFD0, тег 0x0112); 1 — коли нема або не прочиталось. */
export function tiffOrientation(t: Uint8Array): number {
  if (t.length < 8) return 1;
  const le = t[0] === 0x49 && t[1] === 0x49;
  if (!le && !(t[0] === 0x4d && t[1] === 0x4d)) return 1;
  const r16 = (at: number) => (le ? t[at] | (t[at + 1] << 8) : u16(t, at));
  const r32 = (at: number) => (le ? le32(t, at) : u32(t, at));
  const ifd = r32(4);
  if (ifd + 2 > t.length) return 1;
  const n = r16(ifd);
  for (let k = 0; k < n; k += 1) {
    const e = ifd + 2 + k * 12;
    if (e + 12 > t.length) break;
    if (r16(e) === 0x0112 && r16(e + 2) === 3) {
      const v = r16(e + 8);
      return v >= 1 && v <= 8 ? v : 1;
    }
  }
  return 1;
}

/** TIFF з рівно одним полем — Orientation. */
export function orientationTiff(o: number): Uint8Array {
  return new Uint8Array([0x4d, 0x4d, 0, 0x2a, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, o, 0, 0, 0, 0, 0, 0]);
}

const EXIF_HEAD = 'Exif\0\0';

function stripJpeg(b: Uint8Array): Uint8Array | null {
  const head: Uint8Array[] = [b.subarray(0, 2)];
  const rest: Uint8Array[] = [];
  let changed = false;
  let orientation = 1;
  let i = 2;
  let ended = false;
  while (i + 1 < b.length) {
    if (b[i] !== 0xff) return null;
    const m = b[i + 1];
    if (m === 0xff) {
      i += 1;
      continue;
    }
    if (m === 0xd9) {
      rest.push(b.subarray(i, i + 2));
      if (i + 2 < b.length) changed = true;
      ended = true;
      break;
    }
    if ((m >= 0xd0 && m <= 0xd7) || m === 0x01) {
      rest.push(b.subarray(i, i + 2));
      i += 2;
      continue;
    }
    if (i + 4 > b.length) return null;
    const end = i + 2 + u16(b, i + 2);
    if (end > b.length || end < i + 4) return null;
    const p = i + 4;
    if (m === 0xda) {
      let j = end;
      while (j + 1 < b.length && !(b[j] === 0xff && b[j + 1] !== 0 && !(b[j + 1] >= 0xd0 && b[j + 1] <= 0xd7))) j += 1;
      if (j + 1 >= b.length) j = b.length;
      rest.push(b.subarray(i, j));
      i = j;
      continue;
    }
    if (m === 0xe0 && ascii(b, p, 'JFIF\0')) {
      if (end - p > 14) {
        const jfif = b.slice(i, i + 18);
        jfif[2] = 0;
        jfif[3] = 16;
        jfif[16] = 0;
        jfif[17] = 0;
        head.push(jfif);
        changed = true;
      } else head.push(b.subarray(i, end));
    } else if ((m === 0xe2 && ascii(b, p, 'ICC_PROFILE\0')) || (m === 0xee && ascii(b, p, 'Adobe'))) {
      rest.push(b.subarray(i, end));
    } else if ((m >= 0xe0 && m <= 0xef) || m === 0xfe) {
      if (m === 0xe1 && ascii(b, p, EXIF_HEAD) && orientation === 1) orientation = tiffOrientation(b.subarray(p + 6, end));
      changed = true;
    } else {
      rest.push(b.subarray(i, end));
    }
    i = end;
  }
  if (!ended && i < b.length) rest.push(b.subarray(i));
  if (!changed) return null;
  if (orientation !== 1) {
    const tiff = orientationTiff(orientation);
    const len = 2 + EXIF_HEAD.length + tiff.length;
    const app1 = new Uint8Array(2 + len);
    app1.set([0xff, 0xe1, len >> 8, len & 0xff]);
    for (let k = 0; k < EXIF_HEAD.length; k += 1) app1[4 + k] = EXIF_HEAD.charCodeAt(k);
    app1.set(tiff, 4 + EXIF_HEAD.length);
    head.push(app1);
  }
  return concat([...head, ...rest]);
}

let crcTable: Uint32Array | null = null;
function crc32(bytes: Uint8Array): number {
  if (!crcTable) {
    crcTable = new Uint32Array(256);
    for (let n = 0; n < 256; n += 1) {
      let c = n;
      for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      crcTable[n] = c >>> 0;
    }
  }
  let c = 0xffffffff;
  for (let i = 0; i < bytes.length; i += 1) c = crcTable[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

const PNG_DROP = new Set(['tEXt', 'zTXt', 'iTXt', 'eXIf', 'tIME']);

function pngChunk(type: string, data: Uint8Array): Uint8Array {
  const c = new Uint8Array(12 + data.length);
  putBe32(c, 0, data.length);
  for (let k = 0; k < 4; k += 1) c[4 + k] = type.charCodeAt(k);
  c.set(data, 8);
  putBe32(c, 8 + data.length, crc32(c.subarray(4, 8 + data.length)));
  return c;
}

function stripPng(b: Uint8Array): Uint8Array | null {
  const parts: Uint8Array[] = [b.subarray(0, 8)];
  let changed = false;
  let orientation = 1;
  let placed = false;
  let i = 8;
  while (i + 12 <= b.length) {
    const len = u32(b, i);
    const end = i + 12 + len;
    if (end > b.length) return null;
    const type = String.fromCharCode(b[i + 4], b[i + 5], b[i + 6], b[i + 7]);
    if (PNG_DROP.has(type)) {
      if (type === 'eXIf' && orientation === 1) orientation = tiffOrientation(b.subarray(i + 8, i + 8 + len));
      changed = true;
    } else {
      if (!placed && orientation !== 1 && (type === 'IDAT' || type === 'IEND')) {
        parts.push(pngChunk('eXIf', orientationTiff(orientation)));
        placed = true;
      }
      parts.push(b.subarray(i, end));
    }
    i = end;
    if (type === 'IEND') break;
  }
  if (i < b.length) changed = true;
  return changed ? concat(parts) : null;
}

function stripWebp(b: Uint8Array): Uint8Array | null {
  const parts: Uint8Array[] = [b.slice(0, 12)];
  let changed = false;
  let orientation = 1;
  let vp8x: Uint8Array | null = null;
  let i = 12;
  while (i + 8 <= b.length) {
    const len = le32(b, i + 4);
    const end = Math.min(b.length, i + 8 + len + (len & 1));
    if (i + 8 + len > b.length) return null;
    if (ascii(b, i, 'EXIF') || ascii(b, i, 'XMP ')) {
      if (ascii(b, i, 'EXIF') && orientation === 1) {
        const at = ascii(b, i + 8, EXIF_HEAD) ? i + 14 : i + 8;
        orientation = tiffOrientation(b.subarray(at, i + 8 + len));
      }
      changed = true;
    } else if (ascii(b, i, 'VP8X')) {
      vp8x = b.slice(i, end);
      parts.push(vp8x);
    } else parts.push(b.subarray(i, end));
    i = end;
  }
  if (i < b.length) changed = true;
  if (!changed) return null;
  if (vp8x && vp8x.length > 8) vp8x[8] &= ~0x0c;
  if (vp8x && orientation !== 1) {
    vp8x[8] |= 0x08;
    const tiff = orientationTiff(orientation);
    const c = new Uint8Array(8 + tiff.length);
    c.set([0x45, 0x58, 0x49, 0x46]);
    putLe32(c, 4, tiff.length);
    c.set(tiff, 8);
    parts.push(c);
  }
  const out = concat(parts);
  putLe32(out, 4, out.length - 8);
  return out;
}

interface Box {
  type: string;
  start: number;
  body: number;
  end: number;
}

function boxes(b: Uint8Array, from: number, to: number): Box[] {
  const list: Box[] = [];
  let i = from;
  while (i + 8 <= to) {
    let size = u32(b, i);
    let body = i + 8;
    if (size === 1) {
      if (i + 16 > to) break;
      size = u32(b, i + 8) * 2 ** 32 + u32(b, i + 12);
      body = i + 16;
    } else if (size === 0) size = to - i;
    if (size < body - i || i + size > to) break;
    const type = String.fromCharCode(b[i + 4], b[i + 5], b[i + 6], b[i + 7]);
    list.push({ type, start: i, body, end: i + size });
    i += size;
  }
  return list;
}

const isXmpUuid = (b: Uint8Array, x: Box) => x.type === 'uuid' && x.body + 16 <= x.end && XMP_UUID.every((v, k) => b[x.body + k] === v);

function stripIsoBmff(b: Uint8Array): Uint8Array | null {
  let out: Uint8Array | null = null;
  const w = () => (out ??= b.slice());
  const zero = (from: number, to: number) => {
    for (let k = from; k < to; k += 1) if (b[k] !== 0) {
      w().fill(0, from, to);
      return;
    }
  };
  const free = (x: Box) => {
    const o = w();
    o.set([0x66, 0x72, 0x65, 0x65], x.start + 4);
    o.fill(0, x.body, x.end);
  };
  const clock = (x: Box) => {
    if (x.body + 4 > x.end) return;
    const wide = b[x.body] === 1;
    zero(x.body + 4, Math.min(x.end, x.body + 4 + (wide ? 16 : 8)));
  };
  const walk = (from: number, to: number) => {
    for (const x of boxes(b, from, to)) {
      if (x.type === 'udta' || x.type === 'meta' || isXmpUuid(b, x)) free(x);
      else if (x.type === 'mvhd' || x.type === 'tkhd' || x.type === 'mdhd') clock(x);
      else if (x.type === 'trak' || x.type === 'mdia') walk(x.body, x.end);
    }
  };

  const top = boxes(b, 0, b.length);
  for (const x of top) {
    if (x.type === 'moov') walk(x.body, x.end);
    else if (x.type === 'udta' || isXmpUuid(b, x)) free(x);
    else if (x.type === 'meta') heifItems(b, x, zero, () => free(x));
  }
  return out;
}

/** HEIF/AVIF: зображення живе в `meta`, тож нулимо лише Exif/XMP-елементи. */
function heifItems(b: Uint8Array, meta: Box, zero: (from: number, to: number) => void, drop: () => void) {
  const kids = boxes(b, meta.body + 4, meta.end);
  const iinf = kids.find((k) => k.type === 'iinf');
  const iloc = kids.find((k) => k.type === 'iloc');
  if (!kids.some((k) => k.type === 'pitm') || !iinf || !iloc) {
    drop();
    return;
  }
  const idat = kids.find((k) => k.type === 'idat');
  const hidden = new Set<number>();
  const iv = b[iinf.body];
  for (const e of boxes(b, iinf.body + 4 + (iv === 0 ? 2 : 4), iinf.end)) {
    if (e.type !== 'infe') continue;
    const v = b[e.body];
    if (v < 2) continue;
    let p = e.body + 4;
    const id = v === 2 ? u16(b, p) : u32(b, p);
    p += (v === 2 ? 2 : 4) + 2;
    const kind = String.fromCharCode(b[p], b[p + 1], b[p + 2], b[p + 3]);
    p += 4;
    while (p < e.end && b[p] !== 0) p += 1;
    p += 1;
    if (kind === 'Exif' || (kind === 'mime' && ascii(b, p, 'application/rdf+xml'))) hidden.add(id);
  }
  if (!hidden.size) return;

  const v = b[iloc.body];
  let p = iloc.body + 4;
  const offSize = b[p] >> 4;
  const lenSize = b[p] & 15;
  const baseSize = b[p + 1] >> 4;
  const idxSize = v > 0 ? b[p + 1] & 15 : 0;
  p += 2;
  const read = (n: number) => {
    let x = 0;
    for (let k = 0; k < n; k += 1) x = x * 256 + b[p + k];
    p += n;
    return x;
  };
  const count = read(v < 2 ? 2 : 4);
  for (let n = 0; n < count && p < iloc.end; n += 1) {
    const id = read(v < 2 ? 2 : 4);
    const method = v > 0 ? read(2) & 15 : 0;
    read(2);
    const base = read(baseSize);
    const extents = read(2);
    for (let k = 0; k < extents; k += 1) {
      read(idxSize);
      const off = read(offSize);
      const len = read(lenSize);
      if (!hidden.has(id) || len === 0) continue;
      const at = method === 0 ? base + off : method === 1 && idat ? idat.body + base + off : -1;
      if (at >= 0 && at + len <= b.length) zero(at, at + len);
    }
  }
}

/** Чисті байти — або null, якщо чистити нічого (чи формат незнайомий). */
export function stripMetadataBytes(b: Uint8Array): Uint8Array | null {
  try {
    if (b.length > 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return stripJpeg(b);
    if (b.length > 8 && b[0] === 0x89 && ascii(b, 1, 'PNG\r\n\x1a\n')) return stripPng(b);
    if (b.length > 12 && ascii(b, 0, 'RIFF') && ascii(b, 8, 'WEBP')) return stripWebp(b);
    if (b.length > 8 && (ascii(b, 4, 'ftyp') || ascii(b, 4, 'moov') || ascii(b, 4, 'wide') || ascii(b, 4, 'mdat') || ascii(b, 4, 'free'))) return stripIsoBmff(b);
  } catch {
    /* битий файл — їде як є */
  }
  return null;
}

const bytesOf = (blob: Blob): Promise<ArrayBuffer> =>
  typeof blob.arrayBuffer === 'function'
    ? blob.arrayBuffer()
    : new Promise((ok, fail) => {
        const r = new FileReader();
        r.onload = () => ok(r.result as ArrayBuffer);
        r.onerror = () => fail(r.error);
        r.readAsArrayBuffer(blob);
      });

/** Фото до цієї межі читаємо цілим (хвіст після EOI теж чиститься); більше — лише голову. */
export const WHOLE_READ_MAX = 50 * 1024 * 1024;
const BOX_READ_MAX = 64 * 1024 * 1024;
const JPEG_HEAD_MAX = 16 * 1024 * 1024;

const sliceBytes = async (blob: Blob, from: number, to: number) => new Uint8Array(await bytesOf(blob.slice(from, to)));

const isIsoHead = (h: Uint8Array) =>
  h.length > 8 && (ascii(h, 4, 'ftyp') || ascii(h, 4, 'moov') || ascii(h, 4, 'wide') || ascii(h, 4, 'mdat') || ascii(h, 4, 'free'));

/**
 * MP4/MOV будь-якого розміру: читаємо лише заголовки верхніх коробок і самі
 * moov/udta/meta/uuid; mdat не торкаємось — гігабайтне відео не тече в памʼять.
 * HEIF-`meta` (зображення в ньому) чиститься лише цілим файлом до WHOLE_READ_MAX.
 */
async function stripIsoBlob(file: Blob): Promise<Blob | null> {
  const parts: BlobPart[] = [];
  let changed = false;
  let last = 0;
  let off = 0;
  while (off + 8 <= file.size) {
    const h = await sliceBytes(file, off, Math.min(file.size, off + 16));
    let size = u32(h, 0);
    if (size === 1) {
      if (h.length < 16) break;
      size = u32(h, 8) * 2 ** 32 + u32(h, 12);
    } else if (size === 0) size = file.size - off;
    if (size < 8 || off + size > file.size) break;
    const type = String.fromCharCode(h[4], h[5], h[6], h[7]);
    const meta = type === 'moov' || type === 'udta' || type === 'meta' || type === 'uuid';
    if (meta && size > BOX_READ_MAX) console.warn('[stripMetadata] box too big, left as is:', type, size);
    if (meta && size <= BOX_READ_MAX) {
      const box = await sliceBytes(file, off, off + size);
      if (type === 'meta' && boxes(box, 8 + 4, box.length).some((k) => k.type === 'pitm')) {
        if (file.size > WHOLE_READ_MAX) {
          console.warn('[stripMetadata] HEIF over whole-read limit, meta left as is:', file.size);
          off += size;
          continue;
        }
        const whole = stripIsoBmff(new Uint8Array(await bytesOf(file)));
        return whole ? new Blob([whole as BlobPart]) : null;
      }
      const clean = stripIsoBmff(box);
      if (clean) {
        parts.push(file.slice(last, off), clean as BlobPart);
        last = off + size;
        changed = true;
      }
    }
    off += size;
  }
  if (!changed) return null;
  parts.push(file.slice(last));
  return new Blob(parts);
}

/** Перший FFD9 після SOS — кінець кадру (у скані FF завжди з 00 або RST); читаємо шматками. */
async function findEoi(file: Blob, from: number): Promise<number> {
  const step = 4 * 1024 * 1024;
  for (let at = from; at < file.size; at += step) {
    const b = await sliceBytes(file, at, Math.min(file.size, at + step + 1));
    for (let i = 0; i + 1 < b.length; i += 1) if (b[i] === 0xff && b[i + 1] === 0xd9) return at + i;
  }
  return -1;
}

/** Велике JPEG: чистимо голову до сканування й хвіст після EOI, тіло беремо зрізом. */
async function stripJpegHead(file: Blob): Promise<Blob | null> {
  for (let n = 1024 * 1024; ; n *= 4) {
    const head = await sliceBytes(file, 0, Math.min(file.size, n));
    let sos = -1;
    for (let i = 2; i + 3 < head.length; ) {
      if (head[i] !== 0xff) return null;
      if (head[i + 1] === 0xda) {
        sos = i;
        break;
      }
      i += 2 + u16(head, i + 2);
    }
    if (sos > 0) {
      const eoi = await findEoi(file, sos);
      const tail = eoi < 0 ? file.size : eoi + 2;
      const clean = stripJpeg(head.subarray(0, sos));
      if (!clean && tail === file.size) return null;
      return new Blob([(clean ?? head.subarray(0, sos)) as BlobPart, file.slice(sos, tail)]);
    }
    if (n >= JPEG_HEAD_MAX || n >= file.size) return null;
  }
}

/** Той самий файл, якщо чистити нічого; інакше — новий, без метаданих. */
export async function stripMetadata(file: File): Promise<File> {
  let clean: Blob | Uint8Array | null;
  try {
    const head = await sliceBytes(file, 0, Math.min(file.size, 16));
    if (isIsoHead(head)) clean = await stripIsoBlob(file);
    else if (file.size <= WHOLE_READ_MAX) clean = stripMetadataBytes(new Uint8Array(await bytesOf(file)));
    else if (head[0] === 0xff && head[1] === 0xd8 && head[2] === 0xff) clean = await stripJpegHead(file);
    else clean = null;
  } catch {
    return file;
  }
  return clean ? new File([clean as BlobPart], file.name, { type: file.type, lastModified: file.lastModified }) : file;
}
