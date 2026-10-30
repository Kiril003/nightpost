/** Байти з GPS, моделлю й часом — будуємо тут, жодних файлів з інтернету. */
import { deflateSync } from 'zlib';

export const LAT = [50, 1, 27, 1, 1234, 100];
export const SECRET = ['SM-A525F', 'Galaxy', '2026:10:17 08:15:00', 'GPSLatitude', 'Shot at home', '+50.4501+030.5234/', 'SEFH'];

const enc = (s: string) => Uint8Array.from(s, (c) => c.charCodeAt(0) & 0xff);
export const bytes = (s: string) => new TextEncoder().encode(s);
export const cat = (...parts: (Uint8Array | number[])[]) => {
  const out: number[] = [];
  for (const p of parts) out.push(...p);
  return Uint8Array.from(out);
};
const be16 = (v: number) => [(v >> 8) & 0xff, v & 0xff];
const be32 = (v: number) => [(v >>> 24) & 0xff, (v >>> 16) & 0xff, (v >>> 8) & 0xff, v & 0xff];
const le32 = (v: number) => [v & 0xff, (v >>> 8) & 0xff, (v >>> 16) & 0xff, (v >>> 24) & 0xff];

export const has = (hay: Uint8Array, needle: Uint8Array | string) => {
  const n = typeof needle === 'string' ? bytes(needle) : needle;
  outer: for (let i = 0; i + n.length <= hay.length; i += 1) {
    for (let k = 0; k < n.length; k += 1) if (hay[i + k] !== n[k]) continue outer;
    return true;
  }
  return false;
};

type Entry = { tag: number; type: 3 | 4 | 2 | 5; values: number[] | string };

/** TIFF (big-endian) з IFD0 + GPS IFD. */
export function gpsTiff(orientation = 1): Uint8Array {
  const ifd0: Entry[] = [
    { tag: 0x010f, type: 2, values: 'Galaxy\0' },
    { tag: 0x0110, type: 2, values: 'SM-A525F\0' },
    { tag: 0x0112, type: 3, values: [orientation] },
    { tag: 0x0132, type: 2, values: '2026:10:17 08:15:00\0' },
    { tag: 0x8825, type: 4, values: [0] },
  ];
  const gps: Entry[] = [
    { tag: 0x0001, type: 2, values: 'N\0' },
    { tag: 0x0002, type: 5, values: LAT },
    { tag: 0x0003, type: 2, values: 'E\0' },
    { tag: 0x0004, type: 5, values: [30, 1, 31, 1, 2400, 100] },
  ];
  const size = (e: Entry) => (typeof e.values === 'string' ? e.values.length : e.type === 5 ? e.values.length * 4 : e.values.length * (e.type === 3 ? 2 : 4));
  const ifdLen = (list: Entry[]) => 2 + list.length * 12 + 4;
  const ifd0At = 8;
  const gpsAt = ifd0At + ifdLen(ifd0);
  let dataAt = gpsAt + ifdLen(gps);
  ifd0[4].values = [gpsAt];
  const out: number[] = [0x4d, 0x4d, 0, 0x2a, ...be32(ifd0At)];
  const data: number[] = [];
  const write = (list: Entry[]) => {
    out.push(...be16(list.length));
    for (const e of list) {
      const raw: number[] =
        typeof e.values === 'string'
          ? [...enc(e.values)]
          : e.values.flatMap((v) => (e.type === 3 ? be16(v) : be32(v)));
      out.push(...be16(e.tag), ...be16(e.type), ...be32(typeof e.values === 'string' ? e.values.length : e.type === 5 ? e.values.length / 2 : e.values.length));
      if (size(e) <= 4) out.push(...raw, ...new Array(4 - raw.length).fill(0));
      else {
        out.push(...be32(dataAt));
        dataAt += raw.length;
        data.push(...raw);
      }
    }
    out.push(0, 0, 0, 0);
  };
  write(ifd0);
  write(gps);
  return Uint8Array.from([...out, ...data]);
}

/** 8×8 сірий baseline JPEG (magick, -strip): SOI APP0 DQT SOF0 DHT DHT SOS … EOI. */
export const BASE_JPEG = Uint8Array.from(
  ('ffd8ffe000104a46494600010100000100010000ffdb004300100b0c0e0c0a100e0d0e1211101318281a181616183123251d283a333d3c3933383740485c4e404457453738506d51575f626768673e4d71797064785c656763ffc0000b080008000801011100ffc40014000100000000000000000000000000000000ffc40014100100000000000000000000000000000000ffda0008010100003f003fffd9'
    .match(/../g) ?? []).map((h) => parseInt(h, 16)),
);

const seg = (marker: number, payload: Uint8Array) => cat([0xff, marker, ...be16(payload.length + 2)], payload);

/** Як із камери телефона: Exif з GPS, XMP з GPS, коментар, хвіст Samsung після EOI. */
export function gpsJpeg(orientation = 1, base = BASE_JPEG): Uint8Array {
  const app0End = 2 + 2 + ((base[4] << 8) | base[5]);
  return cat(
    base.subarray(0, app0End),
    seg(0xe1, cat(bytes('Exif\0\0'), gpsTiff(orientation))),
    seg(0xe1, bytes('http://ns.adobe.com/xap/1.0/\0<x:xmpmeta><rdf:Description exif:GPSLatitude="50,27.0N"/></x:xmpmeta>')),
    seg(0xfe, bytes('Shot at home')),
    base.subarray(app0End),
    bytes('SEFH\0\0\0\0Image_UTC_Data2026:10:17 08:15:00'),
  );
}

/** Сегменти JPEG: [маркер, початок, кінець]; скан до наступного маркера. */
export function jpegMarkers(b: Uint8Array): number[] {
  const out: number[] = [];
  let i = 2;
  while (i + 1 < b.length) {
    const m = b[i + 1];
    out.push(m);
    if (m === 0xd9) break;
    let end = i + 2 + ((b[i + 2] << 8) | b[i + 3]);
    if (m === 0xda) while (end + 1 < b.length && !(b[end] === 0xff && b[end + 1] !== 0 && (b[end + 1] < 0xd0 || b[end + 1] > 0xd7))) end += 1;
    i = end;
  }
  return out;
}

const crcTable = (() => {
  const t: number[] = [];
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t.push(c >>> 0);
  }
  return t;
})();
const crc = (b: Uint8Array) => {
  let c = 0xffffffff;
  for (const x of b) c = crcTable[(c ^ x) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
};
const chunk = (type: string, data: Uint8Array) => cat(be32(data.length), cat(bytes(type), data), be32(crc(cat(bytes(type), data))));

export const PNG_PIXELS = Uint8Array.from([0, 255, 0, 0, 0, 0, 255, 0, 0, 255, 0, 255, 255, 255]);

/** 2×2 RGB PNG з eXIf (GPS), tEXt, iTXt (XMP), tIME — і сміттям після IEND. */
export function gpsPng(orientation = 1): Uint8Array {
  const ihdr = cat(be32(2), be32(2), [8, 2, 0, 0, 0]);
  return cat(
    [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a],
    chunk('IHDR', ihdr),
    chunk('eXIf', gpsTiff(orientation)),
    chunk('tEXt', bytes('Comment\0Shot at home')),
    chunk('iTXt', bytes('XML:com.adobe.xmp\0\0\0\0\0<rdf:Description exif:GPSLatitude="50,27.0N"/>')),
    chunk('tIME', Uint8Array.from([0x07, 0xea, 10, 17, 8, 15, 0])),
    chunk('IDAT', new Uint8Array(deflateSync(PNG_PIXELS))),
    chunk('IEND', new Uint8Array()),
    bytes('SEFH'),
  );
}

export function pngChunks(b: Uint8Array): { type: string; data: Uint8Array; crcOk: boolean }[] {
  const out: { type: string; data: Uint8Array; crcOk: boolean }[] = [];
  let i = 8;
  while (i + 12 <= b.length) {
    const len = ((b[i] << 24) >>> 0) + ((b[i + 1] << 16) | (b[i + 2] << 8) | b[i + 3]);
    const type = String.fromCharCode(...b.subarray(i + 4, i + 8));
    const stored = ((b[i + 8 + len] << 24) >>> 0) + ((b[i + 9 + len] << 16) | (b[i + 10 + len] << 8) | b[i + 11 + len]);
    out.push({ type, data: b.subarray(i + 8, i + 8 + len), crcOk: stored === crc(b.subarray(i + 4, i + 8 + len)) });
    i += 12 + len;
  }
  return out;
}

const riff = (type: string, data: Uint8Array) => cat(bytes(type), le32(data.length), data, data.length & 1 ? [0] : []);

/** Розширений WebP: VP8X з прапорцями EXIF|XMP, кадр VP8L, EXIF (GPS) і XMP. */
export function gpsWebp(orientation = 1): Uint8Array {
  const body = cat(
    riff('VP8X', Uint8Array.from([0x0c, 0, 0, 0, 1, 0, 0, 1, 0, 0])),
    riff('VP8L', Uint8Array.from([0x2f, 1, 0x40, 0, 0, 0x07, 0x10, 0x11, 0x11, 0x88, 0x88, 0xfe, 0x07, 0])),
    riff('EXIF', cat(bytes('Exif\0\0'), gpsTiff(orientation))),
    riff('XMP ', bytes('<rdf:Description exif:GPSLatitude="50,27.0N"/>')),
  );
  return cat(bytes('RIFF'), le32(body.length + 4), bytes('WEBP'), body);
}

export const box = (type: string, ...kids: (Uint8Array | number[])[]) => {
  const body = cat(...kids);
  return cat(be32(body.length + 8), enc(type), body);
};
const full = (type: string, version: number, ...kids: (Uint8Array | number[])[]) => box(type, Uint8Array.from([version, 0, 0, 0]), ...kids);

export const XMP_UUID = [0xbe, 0x7a, 0xcf, 0xcb, 0x97, 0xa9, 0x42, 0xe8, 0x9c, 0x71, 0x99, 0x94, 0x91, 0xe3, 0xaf, 0xac];
export const FRAME = bytes('FRAME-BYTES-0123456789');
const CREATED = be32(0xe5a1b2c3);

/**
 * MP4 «faststart» (moov ДО mdat): mvhd/tkhd/mdhd з часом, stco на кадр,
 * udta/©xyz, moov/meta (Apple location), trak/udta, XMP-uuid на верхньому рівні.
 */
export function gpsMp4(): Uint8Array {
  const ftyp = box('ftyp', bytes('isom'), be32(0x200), bytes('isomiso2mp41'));
  const xyz = box('\xa9xyz', be16(18), be16(0x15c7), bytes('+50.4501+030.5234/'));
  const udta = box('udta', xyz, box('\xa9mod', be16(8), be16(0), bytes('SM-A525F')));
  const meta = full('meta', 0, box('hdlr', new Uint8Array(4), bytes('mdta'), new Uint8Array(13)), box('keys', new Uint8Array(4), be32(1), box('mdta', bytes('com.apple.quicktime.location.ISO6709'))), box('ilst', box('\0\0\0\x01', box('data', new Uint8Array(8), bytes('+50.4501+030.5234/')))));
  const build = (chunkOffset: number) => {
    const stco = full('stco', 0, be32(1), be32(chunkOffset));
    const trak = box(
      'trak',
      full('tkhd', 0, CREATED, CREATED, new Uint8Array(72)),
      box('mdia', full('mdhd', 0, CREATED, CREATED, new Uint8Array(12)), box('minf', box('stbl', stco))),
      box('udta', box('\xa9mod', be16(8), be16(0), bytes('SM-A525F'))),
    );
    const moov = box('moov', full('mvhd', 0, CREATED, CREATED, new Uint8Array(88)), trak, udta, meta);
    const xmp = box('uuid', Uint8Array.from(XMP_UUID), bytes('<rdf:Description exif:GPSLatitude="50,27.0N"/>'));
    return { head: cat(ftyp, moov, xmp), mdat: box('mdat', FRAME) };
  };
  const probe = build(0);
  const offset = probe.head.length + 8;
  const real = build(offset);
  return cat(real.head, real.mdat);
}

export type ParsedBox = { type: string; start: number; end: number; kids?: ParsedBox[] };
const CONTAINERS = new Set(['moov', 'trak', 'mdia', 'minf', 'stbl', 'udta']);

/** Повне дерево: кожен розмір мусить точно лягати в батька. */
export function parseBoxes(b: Uint8Array, from = 0, to = b.length): ParsedBox[] {
  const out: ParsedBox[] = [];
  let i = from;
  while (i < to) {
    const size = ((b[i] << 24) >>> 0) + ((b[i + 1] << 16) | (b[i + 2] << 8) | b[i + 3]);
    const type = String.fromCharCode(...b.subarray(i + 4, i + 8));
    if (size < 8 || i + size > to) throw new Error(`bad box ${type} at ${i}`);
    out.push({ type, start: i, end: i + size, ...(CONTAINERS.has(type) ? { kids: parseBoxes(b, i + 8, i + size) } : {}) });
    i += size;
  }
  return out;
}

/** HEIC: meta(pitm, iinf[hvc1, Exif, mime XMP], iloc v0 → mdat) + mdat. */
export function gpsHeic(): { file: Uint8Array; image: Uint8Array } {
  const image = bytes('HEVC-IMAGE-PAYLOAD');
  const exif = cat(be32(6), bytes('Exif\0\0'), gpsTiff(6));
  const xmp = bytes('<rdf:Description exif:GPSLatitude="50,27.0N"/>');
  const infe = (id: number, type: string, extra = new Uint8Array()) => full('infe', 2, be16(id), be16(0), bytes(type), [0], extra);
  const ftyp = box('ftyp', bytes('heic'), be32(0), bytes('mif1heic'));
  const build = (at: number) => {
    const iloc = full('iloc', 0, [0x44, 0x00], be16(3),
      be16(1), be16(0), be16(1), be32(at), be32(image.length),
      be16(2), be16(0), be16(1), be32(at + image.length), be32(exif.length),
      be16(3), be16(0), be16(1), be32(at + image.length + exif.length), be32(xmp.length));
    return full('meta', 0,
      full('hdlr', 0, new Uint8Array(4), bytes('pict'), new Uint8Array(13)),
      full('pitm', 0, be16(1)),
      iloc,
      full('iinf', 0, be16(3), infe(1, 'hvc1'), infe(2, 'Exif'), infe(3, 'mime', cat(bytes('application/rdf+xml'), [0]))));
  };
  const at = ftyp.length + build(0).length + 8;
  return { file: cat(ftyp, build(at), box('mdat', image, exif, xmp)), image };
}

/** jsdom не має Blob.arrayBuffer — браузер має; читаємо через FileReader. */
export function readBlob(blob: Blob): Promise<Uint8Array> {
  return new Promise((ok, fail) => {
    const r = new FileReader();
    r.onload = () => ok(new Uint8Array(r.result as ArrayBuffer));
    r.onerror = () => fail(r.error);
    r.readAsArrayBuffer(blob);
  });
}

export function polyfillBlobArrayBuffer() {
  if (typeof Blob.prototype.arrayBuffer !== 'function') {
    Object.defineProperty(Blob.prototype, 'arrayBuffer', {
      configurable: true,
      value(this: Blob) {
        return readBlob(this).then((u) => u.buffer);
      },
    });
  }
}

const unhex = (h: string) => Uint8Array.from((h.match(/../g) ?? []).map((x) => parseInt(x, 16)));

/** Розшифрувати те, що пішло на вузол, ключем із тіла листа. */
export async function openSealed(cipher: Blob, keyHex: string, nonceHex: string): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey('raw', unhex(keyHex), 'AES-GCM', false, ['decrypt']);
  return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unhex(nonceHex) }, key, (await readBlob(cipher)) as BufferSource));
}

/** Orientation з big-endian TIFF, який кладе чистка (IFD0, перше поле). */
export const orientationOf = (t: Uint8Array) => (t[10] === 0x01 && t[11] === 0x12 ? (t[18] << 8) | t[19] : 0);
