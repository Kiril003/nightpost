/**
 * Метадані не їдуть людям. Червоне без stripMetadata: мале фото й відео
 * лягали на вузол байт-у-байт — разом із GPS, моделлю телефона й часом.
 */
import { describe, expect, it } from 'vitest';
import { inflateSync } from 'zlib';
import { WHOLE_READ_MAX, stripMetadata, stripMetadataBytes, tiffOrientation } from '../stripMetadata';
import {
  BASE_JPEG, FRAME, PNG_PIXELS, SECRET, bytes, cat, gpsHeic, gpsJpeg, gpsMp4, gpsPng, gpsWebp, has, jpegMarkers,
  parseBoxes, pngChunks, readBlob, type ParsedBox,
} from './metadataFixtures';

const clean = (b: Uint8Array) => {
  const out = stripMetadataBytes(b);
  expect(out).not.toBeNull();
  return out as Uint8Array;
};
const noSecrets = (b: Uint8Array) => {
  for (const s of SECRET) expect(has(b, s), s).toBe(false);
  expect(has(b, Uint8Array.from([0x88, 0x25])), 'GPS IFD pointer').toBe(false);
};
const exifTiffs = (b: Uint8Array) => {
  const out: Uint8Array[] = [];
  let i = 2;
  while (i + 4 < b.length && b[i + 1] !== 0xda) {
    const end = i + 2 + ((b[i + 2] << 8) | b[i + 3]);
    if (b[i + 1] === 0xe1 && has(b.subarray(i + 4, i + 10), 'Exif')) out.push(b.subarray(i + 10, end));
    i = end;
  }
  return out;
};
const find = (list: ParsedBox[], type: string): ParsedBox[] =>
  list.flatMap((x) => [...(x.type === type ? [x] : []), ...find(x.kids ?? [], type)]);

describe('JPEG', () => {
  it('без Exif/XMP/COM/хвоста; SOI, SOF0, SOS і EOI на місці, пікселі ті самі', () => {
    const src = gpsJpeg();
    expect(has(src, 'GPSLatitude')).toBe(true);
    const out = clean(src);
    noSecrets(out);
    expect(exifTiffs(out)).toHaveLength(0);
    expect(out[0]).toBe(0xff);
    expect(out[1]).toBe(0xd8);
    expect(out.subarray(-2)).toEqual(Uint8Array.from([0xff, 0xd9]));
    expect(jpegMarkers(out)).toEqual([0xe0, 0xdb, 0xc0, 0xc4, 0xc4, 0xda, 0xd9]);
    expect(out).toEqual(BASE_JPEG);
  });

  it('поворот із камери лишається: новий Exif рівно з одним полем Orientation', () => {
    const out = clean(gpsJpeg(6));
    noSecrets(out);
    const tiffs = exifTiffs(out);
    expect(tiffs).toHaveLength(1);
    expect(tiffOrientation(tiffs[0])).toBe(6);
    expect((tiffs[0][8] << 8) | tiffs[0][9]).toBe(1);
    expect(jpegMarkers(out)).toEqual([0xe0, 0xe1, 0xdb, 0xc0, 0xc4, 0xc4, 0xda, 0xd9]);
  });

  it('скан із FF00 і RST не ріжеться; ICC і Adobe лишаються', () => {
    const icc = cat([0xff, 0xe2, 0, 16], bytes('ICC_PROFILE\0'), [1, 1]);
    const adobe = cat([0xff, 0xee, 0, 14], bytes('Adobe'), [0, 100, 0, 0, 0, 0, 1]);
    const scan = Uint8Array.from([0x12, 0xff, 0x00, 0x34, 0xff, 0xd0, 0x56, 0xff, 0xff, 0xd9]);
    const sos = Uint8Array.from([0xff, 0xda, 0, 8, 1, 1, 0, 0, 0x3f, 0]);
    const src = cat([0xff, 0xd8], [0xff, 0xe1, 0, 8], bytes('Exif\0\0'), icc, adobe, [0xff, 0xfe, 0, 4, 0x41, 0x42], sos, scan);
    const out = clean(src);
    expect(out).toEqual(cat([0xff, 0xd8], icc, adobe, sos, scan.subarray(0, 7), [0xff, 0xd9]));
  });

  it('чисте фото не переписується', () => {
    expect(stripMetadataBytes(BASE_JPEG)).toBeNull();
  });
});

describe('PNG', () => {
  it('без eXIf/tEXt/iTXt/tIME і хвоста; CRC цілі, пікселі ті самі', () => {
    const out = clean(gpsPng());
    noSecrets(out);
    const chunks = pngChunks(out);
    expect(chunks.map((c) => c.type)).toEqual(['IHDR', 'IDAT', 'IEND']);
    expect(chunks.every((c) => c.crcOk)).toBe(true);
    expect(new Uint8Array(inflateSync(chunks[1].data))).toEqual(PNG_PIXELS);
    expect(out.subarray(-4)).toEqual(Uint8Array.from([0xae, 0x42, 0x60, 0x82]));
  });

  it('поворот — новий eXIf лише з Orientation, до IDAT', () => {
    const chunks = pngChunks(clean(gpsPng(8)));
    expect(chunks.map((c) => c.type)).toEqual(['IHDR', 'eXIf', 'IDAT', 'IEND']);
    expect(chunks.every((c) => c.crcOk)).toBe(true);
    expect(tiffOrientation(chunks[1].data)).toBe(8);
    expect(chunks[1].data.length).toBe(26);
  });
});

describe('WebP', () => {
  it('без EXIF/XMP; прапорці VP8X і розмір RIFF правильні; кадр цілий', () => {
    const src = gpsWebp();
    const out = clean(src);
    noSecrets(out);
    expect(out[20] & 0x0c).toBe(0);
    expect(out[4] | (out[5] << 8) | (out[6] << 16)).toBe(out.length - 8);
    expect(has(out, 'VP8L')).toBe(true);
    expect(has(out, 'EXIF')).toBe(false);
  });

  it('поворот лишається окремим EXIF з одним полем', () => {
    const out = clean(gpsWebp(3));
    noSecrets(out);
    expect(out[20] & 0x0c).toBe(0x08);
    const at = out.length - 26;
    expect(String.fromCharCode(...out.subarray(at - 8, at - 4))).toBe('EXIF');
    expect(tiffOrientation(out.subarray(at))).toBe(3);
  });
});

describe('MP4/MOV', () => {
  it('©xyz, udta, meta, XMP-uuid — у `free` з нулями; дерево й stco цілі; час — нуль', () => {
    const src = gpsMp4();
    expect(has(src, '+50.4501+030.5234/')).toBe(true);
    const out = clean(src);
    noSecrets(out);
    expect(out.length).toBe(src.length);
    const tree = parseBoxes(out);
    expect(tree.map((x) => x.type)).toEqual(['ftyp', 'moov', 'free', 'mdat']);
    for (const t of ['udta', 'meta', 'uuid', '\xa9xyz']) expect(find(tree, t)).toHaveLength(0);
    const stco = find(tree, 'stco')[0];
    const off = new DataView(out.buffer, out.byteOffset).getUint32(stco.start + 16);
    expect(Array.from(out.subarray(off, off + FRAME.length))).toEqual(Array.from(FRAME));
    for (const t of ['mvhd', 'tkhd', 'mdhd']) {
      const x = find(tree, t)[0];
      expect(out.subarray(x.start + 12, x.start + 20)).toEqual(new Uint8Array(8));
    }
  });
});

describe('HEIC', () => {
  it('Exif- і XMP-елементи — нулі; зображення й структура ті самі', () => {
    const { file, image } = gpsHeic();
    const out = clean(file);
    noSecrets(out);
    expect(out.length).toBe(file.length);
    expect(has(out, image)).toBe(true);
    expect(parseBoxes(out).map((x) => x.type)).toEqual(['ftyp', 'meta', 'mdat']);
    expect(has(out, 'pitm') && has(out, 'iloc') && has(out, 'iinf')).toBe(true);
  });
});

describe('stripMetadata(File)', () => {
  it('віддає новий File з тим самим імʼям і типом', async () => {
    const f = new File([gpsJpeg() as BlobPart], 'IMG_0001.jpg', { type: 'image/jpeg' });
    const out = await stripMetadata(f);
    expect(out).not.toBe(f);
    expect(out.name).toBe('IMG_0001.jpg');
    expect(out.type).toBe('image/jpeg');
    noSecrets(await readBlob(out));
  });

  it('відео не читається цілим: лише заголовки й moov, GPS геть, кадр той самий', async () => {
    const f = new File([gpsMp4() as BlobPart], 'VID_0001.mp4', { type: 'video/mp4' });
    let whole = 0;
    const noWhole = () => {
      whole += 1;
      throw new Error('whole file read');
    };
    Object.defineProperty(f, 'arrayBuffer', { value: noWhole });
    const realSlice = f.slice.bind(f);
    Object.defineProperty(f, 'slice', {
      value: (a?: number, b?: number) => {
        if ((a ?? 0) === 0 && (b ?? f.size) >= f.size) noWhole();
        return realSlice(a, b);
      },
    });
    const out = await stripMetadata(f);
    expect(whole).toBe(0);
    expect(out).not.toBe(f);
    const plain = await readBlob(out);
    for (const s of SECRET) expect(has(plain, s), s).toBe(false);
    expect(has(plain, FRAME)).toBe(true);
    expect(plain.length).toBe(f.size);
  });

  it('велике фото понад межу: чиститься голова, тіло береться зрізом', async () => {
    const jpeg = gpsJpeg();
    let eoi = jpeg.length - 2;
    while (!(jpeg[eoi] === 0xff && jpeg[eoi + 1] === 0xd9)) eoi -= 1;
    const scan = new Uint8Array(WHOLE_READ_MAX);
    const f = new File([jpeg.subarray(0, eoi) as BlobPart, scan, jpeg.subarray(eoi) as BlobPart], 'PANO.jpg', { type: 'image/jpeg' });
    Object.defineProperty(f, 'arrayBuffer', { value: () => Promise.reject(new Error('whole file read')) });
    const out = await stripMetadata(f);
    expect(out).not.toBe(f);
    noSecrets(await readBlob(out.slice(0, 4096)));
    const tail = await readBlob(out.slice(out.size - 4096));
    noSecrets(tail);
    expect(Array.from(tail.subarray(tail.length - 2))).toEqual([0xff, 0xd9]);
    expect(out.size).toBeGreaterThan(WHOLE_READ_MAX);
  });

  it('незнайомий формат — той самий обʼєкт', async () => {
    const f = new File([Uint8Array.from([1, 2, 3, 4])], 'a.bin');
    expect(await stripMetadata(f)).toBe(f);
  });
});
