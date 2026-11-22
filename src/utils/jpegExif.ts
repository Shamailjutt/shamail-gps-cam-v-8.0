export interface ExifRational {
  numerator: number;
  denominator: number;
}

export interface ParsedExifMetadata {
  hasExif: boolean;
  dateTime?: string;
  dateTimeOriginal?: string;
  dateTimeDigitized?: string;
  offsetTime?: string;
  offsetTimeOriginal?: string;
  offsetTimeDigitized?: string;
  subSecTime?: string;
  subSecTimeOriginal?: string;
  subSecTimeDigitized?: string;
  dateTimeMillis?: number | null;
  latitude?: number | null;
  longitude?: number | null;
  altitudeMeters?: number | null;
  altitudeRef?: number | null;
  gpsDateStamp?: string;
  gpsTimeStamp?: [ExifRational, ExifRational, ExifRational];
  gpsProcessingMethod?: string;
  make?: string;
  model?: string;
  orientation?: number;
  software?: string;
  artist?: string;
  copyright?: string;
  imageDescription?: string;
  userComment?: string;
  exposureTime?: ExifRational;
  fNumber?: ExifRational;
  exposureProgram?: number;
  isoSpeedRatings?: number[];
  shutterSpeedValue?: ExifRational;
  apertureValue?: ExifRational;
  exposureBiasValue?: ExifRational;
  flash?: number;
  focalLength?: ExifRational;
  exposureMode?: number;
  whiteBalance?: number;
  focalLengthIn35mmFilm?: number;
  lensMake?: string;
  lensModel?: string;
}

export interface WriteExifOptions {
  dateTimeMillis: number;
  latitude?: number | null;
  longitude?: number | null;
  altitudeMeters?: number | null;
  altitudeRef?: number | null;
  gpsProcessingMethod?: string | null;
  originalExif?: ParsedExifMetadata | null;
  organizationName?: string;
  software?: string;
}

export const SHAMAIL_IMAGE_DESCRIPTION = 'SHAMAIL PERA | VERIFIED_DEVICE_CAPTURE';
export const SHAMAIL_USER_COMMENT = 'VERIFIED_DEVICE_CAPTURE';

const TYPE_BYTE = 1;
const TYPE_ASCII = 2;
const TYPE_SHORT = 3;
const TYPE_LONG = 4;
const TYPE_RATIONAL = 5;
const TYPE_UNDEFINED = 7;
const TYPE_SRATIONAL = 10;

const TYPE_SIZES: Record<number, number> = {
  [TYPE_BYTE]: 1,
  [TYPE_ASCII]: 1,
  [TYPE_SHORT]: 2,
  [TYPE_LONG]: 4,
  [TYPE_RATIONAL]: 8,
  [TYPE_UNDEFINED]: 1,
  [TYPE_SRATIONAL]: 8,
};

function pad2(n: number): string {
  return String(n).padStart(2, '0');
}

export function formatExifDateTime(millis: number): string {
  const d = new Date(millis);
  return `${d.getFullYear()}:${pad2(d.getMonth() + 1)}:${pad2(d.getDate())} ${pad2(
    d.getHours()
  )}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`;
}

export function formatExifOffset(millis: number): string {
  const d = new Date(millis);
  const totalMin = -d.getTimezoneOffset();
  const sign = totalMin >= 0 ? '+' : '-';
  const absMin = Math.abs(totalMin);
  return `${sign}${pad2(Math.floor(absMin / 60))}:${pad2(absMin % 60)}`;
}

export function formatFileStamp(millis: number): string {
  const d = new Date(millis);
  return `${d.getFullYear()}${pad2(d.getMonth() + 1)}${pad2(d.getDate())}_${pad2(
    d.getHours()
  )}${pad2(d.getMinutes())}${pad2(d.getSeconds())}`;
}

export function parseExifDateTimeToMillis(raw?: string | null): number | null {
  if (!raw) return null;
  const trimmed = raw.trim();
  const m = /^(\d{4}):(\d{2}):(\d{2})\s+(\d{2}):(\d{2}):(\d{2})$/.exec(trimmed);
  if (!m) return null;
  const year = Number(m[1]);
  const month = Number(m[2]) - 1;
  const day = Number(m[3]);
  const hour = Number(m[4]);
  const min = Number(m[5]);
  const sec = Number(m[6]);
  const dt = new Date(year, month, day, hour, min, sec, 0);
  const ts = dt.getTime();
  return Number.isFinite(ts) ? ts : null;
}

export function dataUrlToUint8Array(dataUrl: string): Uint8Array {
  const commaIdx = dataUrl.indexOf(',');
  const base64 = commaIdx >= 0 ? dataUrl.slice(commaIdx + 1) : dataUrl;
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

export function uint8ArrayToDataUrl(bytes: Uint8Array, mimeType = 'image/jpeg'): string {
  let binary = '';
  const chunkSize = 0x8000;
  for (let i = 0; i < bytes.length; i += chunkSize) {
    const chunk = bytes.subarray(i, Math.min(i + chunkSize, bytes.length));
    binary += String.fromCharCode(...chunk);
  }
  return `data:${mimeType};base64,${btoa(binary)}`;
}

export async function canvasToJpegBytes(
  canvas: HTMLCanvasElement,
  qualityDecimal: number
): Promise<Uint8Array> {
  if (typeof canvas.toBlob === 'function') {
    const blob = await new Promise<Blob | null>((resolve) => {
      canvas.toBlob((b) => resolve(b), 'image/jpeg', qualityDecimal);
    });
    if (blob) {
      const buf = await blob.arrayBuffer();
      return new Uint8Array(buf);
    }
  }
  return dataUrlToUint8Array(canvas.toDataURL('image/jpeg', qualityDecimal));
}

export async function computeSha256Hex(bytes: Uint8Array): Promise<string> {
  if (typeof crypto !== 'undefined' && crypto.subtle) {
    const copy = new Uint8Array(bytes.byteLength);
    copy.set(bytes);
    const digest = await crypto.subtle.digest('SHA-256', copy.buffer);
    return Array.from(new Uint8Array(digest))
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('');
  }
  // Deterministic fallback hash when WebCrypto subtle is unavailable
  let h1 = 0x811c9dc5;
  for (let i = 0; i < bytes.length; i++) {
    h1 ^= bytes[i];
    h1 = Math.imul(h1, 0x01000193);
  }
  return `${bytes.length.toString(16)}_${(h1 >>> 0).toString(16).padStart(8, '0')}`;
}

function readAscii(bytes: Uint8Array, offset: number, count: number): string {
  const end = Math.min(bytes.length, offset + count);
  let out = '';
  for (let i = offset; i < end; i++) {
    const code = bytes[i];
    if (code === 0) break;
    out += String.fromCharCode(code);
  }
  return out.trim();
}

function readUserComment(bytes: Uint8Array, offset: number, count: number): string {
  const end = Math.min(bytes.length, offset + count);
  if (end <= offset) return '';
  let start = offset;
  const header = readAsciiRaw(bytes, offset, Math.min(8, end - offset));
  if (header.startsWith('ASCII') || header.startsWith('UNICODE') || header.startsWith('JIS')) {
    start = Math.min(end, offset + 8);
  }
  let out = '';
  for (let i = start; i < end; i++) {
    const c = bytes[i];
    if (c !== 0) out += String.fromCharCode(c);
  }
  return out.trim();
}

function readAsciiRaw(bytes: Uint8Array, offset: number, count: number): string {
  const end = Math.min(bytes.length, offset + count);
  let out = '';
  for (let i = offset; i < end; i++) {
    out += String.fromCharCode(bytes[i]);
  }
  return out;
}

interface RawIfdValue {
  tag: number;
  type: number;
  count: number;
  dataOffset: number;
}

function readIfdEntries(
  view: DataView,
  tiffStart: number,
  ifdRelOffset: number,
  littleEndian: boolean
): RawIfdValue[] {
  const absIfd = tiffStart + ifdRelOffset;
  if (absIfd < tiffStart || absIfd + 2 > view.byteLength) return [];
  const count = view.getUint16(absIfd, littleEndian);
  const results: RawIfdValue[] = [];
  for (let i = 0; i < count; i++) {
    const entryOff = absIfd + 2 + i * 12;
    if (entryOff + 12 > view.byteLength) break;
    const tag = view.getUint16(entryOff, littleEndian);
    const type = view.getUint16(entryOff + 2, littleEndian);
    const valCount = view.getUint32(entryOff + 4, littleEndian);
    const unitSize = TYPE_SIZES[type] ?? 1;
    const totalSize = valCount * unitSize;
    let dataOffset = entryOff + 8;
    if (totalSize > 4) {
      const relPtr = view.getUint32(entryOff + 8, littleEndian);
      dataOffset = tiffStart + relPtr;
    }
    if (dataOffset >= tiffStart && dataOffset + totalSize <= view.byteLength) {
      results.push({ tag, type, count: valCount, dataOffset });
    }
  }
  return results;
}

function readNumbers(
  view: DataView,
  entry: RawIfdValue,
  littleEndian: boolean
): number[] {
  const nums: number[] = [];
  for (let i = 0; i < entry.count; i++) {
    if (entry.type === TYPE_SHORT) {
      nums.push(view.getUint16(entry.dataOffset + i * 2, littleEndian));
    } else if (entry.type === TYPE_LONG) {
      nums.push(view.getUint32(entry.dataOffset + i * 4, littleEndian));
    } else if (entry.type === TYPE_BYTE) {
      nums.push(view.getUint8(entry.dataOffset + i));
    }
  }
  return nums;
}

function readRationals(
  view: DataView,
  entry: RawIfdValue,
  littleEndian: boolean
): ExifRational[] {
  const out: ExifRational[] = [];
  const signed = entry.type === TYPE_SRATIONAL;
  for (let i = 0; i < entry.count; i++) {
    const off = entry.dataOffset + i * 8;
    if (off + 8 > view.byteLength) break;
    const num = signed ? view.getInt32(off, littleEndian) : view.getUint32(off, littleEndian);
    const den = signed ? view.getInt32(off + 4, littleEndian) : view.getUint32(off + 4, littleEndian);
    out.push({ numerator: num, denominator: den });
  }
  return out;
}

function dmsRationalsToDecimal(rats: ExifRational[], ref: string): number | null {
  if (rats.length < 3) return null;
  const toVal = (r: ExifRational) => (r.denominator !== 0 ? r.numerator / r.denominator : 0);
  const deg = toVal(rats[0]);
  const min = toVal(rats[1]);
  const sec = toVal(rats[2]);
  if (!Number.isFinite(deg) || !Number.isFinite(min) || !Number.isFinite(sec)) return null;
  let decimal = deg + min / 60 + sec / 3600;
  if (ref.toUpperCase() === 'S' || ref.toUpperCase() === 'W') {
    decimal = -Math.abs(decimal);
  }
  return Number.isFinite(decimal) ? decimal : null;
}

export function parseJpegExif(bytes: Uint8Array): ParsedExifMetadata {
  const empty: ParsedExifMetadata = { hasExif: false };
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) {
    return empty;
  }

  let offset = 2;
  let tiffStart = -1;
  let app1End = -1;

  while (offset + 3 < bytes.length) {
    if (bytes[offset] !== 0xff) {
      offset++;
      continue;
    }
    const marker = bytes[offset + 1];
    if (marker === 0xff) {
      offset++;
      continue;
    }
    if (marker === 0xd9 || marker === 0xda) break; // EOI or SOS
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
      offset += 2;
      continue;
    }
    const segLen = (bytes[offset + 2] << 8) | bytes[offset + 3];
    if (segLen < 2 || offset + 2 + segLen > bytes.length) break;

    if (
      marker === 0xe1 &&
      segLen >= 10 &&
      bytes[offset + 4] === 0x45 && // E
      bytes[offset + 5] === 0x78 && // x
      bytes[offset + 6] === 0x69 && // i
      bytes[offset + 7] === 0x66 && // f
      bytes[offset + 8] === 0x00 &&
      bytes[offset + 9] === 0x00
    ) {
      tiffStart = offset + 10;
      app1End = offset + 2 + segLen;
      break;
    }
    offset += 2 + segLen;
  }

  if (tiffStart < 0 || tiffStart + 8 > app1End) {
    return empty;
  }

  const view = new DataView(bytes.buffer, bytes.byteOffset, app1End);
  const endianMark = view.getUint16(tiffStart, false);
  const littleEndian = endianMark === 0x4949;
  if (!littleEndian && endianMark !== 0x4d4d) {
    return empty;
  }
  const magic = view.getUint16(tiffStart + 2, littleEndian);
  if (magic !== 0x002a) return empty;
  const ifd0Offset = view.getUint32(tiffStart + 4, littleEndian);

  const meta: ParsedExifMetadata = { hasExif: true };
  let exifIfdOffset: number | null = null;
  let gpsIfdOffset: number | null = null;

  const ifd0 = readIfdEntries(view, tiffStart, ifd0Offset, littleEndian);
  for (const e of ifd0) {
    switch (e.tag) {
      case 0x010e:
        meta.imageDescription = readAscii(bytes, e.dataOffset, e.count);
        break;
      case 0x010f:
        meta.make = readAscii(bytes, e.dataOffset, e.count);
        break;
      case 0x0110:
        meta.model = readAscii(bytes, e.dataOffset, e.count);
        break;
      case 0x0112:
        meta.orientation = readNumbers(view, e, littleEndian)[0];
        break;
      case 0x0131:
        meta.software = readAscii(bytes, e.dataOffset, e.count);
        break;
      case 0x0132:
        meta.dateTime = readAscii(bytes, e.dataOffset, e.count);
        break;
      case 0x013b:
        meta.artist = readAscii(bytes, e.dataOffset, e.count);
        break;
      case 0x8298:
        meta.copyright = readAscii(bytes, e.dataOffset, e.count);
        break;
      case 0x8769:
        exifIfdOffset = readNumbers(view, e, littleEndian)[0] ?? null;
        break;
      case 0x8825:
        gpsIfdOffset = readNumbers(view, e, littleEndian)[0] ?? null;
        break;
    }
  }

  if (exifIfdOffset !== null) {
    const exifIfd = readIfdEntries(view, tiffStart, exifIfdOffset, littleEndian);
    for (const e of exifIfd) {
      switch (e.tag) {
        case 0x829a:
          meta.exposureTime = readRationals(view, e, littleEndian)[0];
          break;
        case 0x829d:
          meta.fNumber = readRationals(view, e, littleEndian)[0];
          break;
        case 0x8822:
          meta.exposureProgram = readNumbers(view, e, littleEndian)[0];
          break;
        case 0x8827:
          meta.isoSpeedRatings = readNumbers(view, e, littleEndian);
          break;
        case 0x9003:
          meta.dateTimeOriginal = readAscii(bytes, e.dataOffset, e.count);
          break;
        case 0x9004:
          meta.dateTimeDigitized = readAscii(bytes, e.dataOffset, e.count);
          break;
        case 0x9010:
          meta.offsetTime = readAscii(bytes, e.dataOffset, e.count);
          break;
        case 0x9011:
          meta.offsetTimeOriginal = readAscii(bytes, e.dataOffset, e.count);
          break;
        case 0x9012:
          meta.offsetTimeDigitized = readAscii(bytes, e.dataOffset, e.count);
          break;
        case 0x9201:
          meta.shutterSpeedValue = readRationals(view, e, littleEndian)[0];
          break;
        case 0x9202:
          meta.apertureValue = readRationals(view, e, littleEndian)[0];
          break;
        case 0x9204:
          meta.exposureBiasValue = readRationals(view, e, littleEndian)[0];
          break;
        case 0x9209:
          meta.flash = readNumbers(view, e, littleEndian)[0];
          break;
        case 0x920a:
          meta.focalLength = readRationals(view, e, littleEndian)[0];
          break;
        case 0x9286:
          meta.userComment = readUserComment(bytes, e.dataOffset, e.count);
          break;
        case 0x9290:
          meta.subSecTime = readAscii(bytes, e.dataOffset, e.count);
          break;
        case 0x9291:
          meta.subSecTimeOriginal = readAscii(bytes, e.dataOffset, e.count);
          break;
        case 0x9292:
          meta.subSecTimeDigitized = readAscii(bytes, e.dataOffset, e.count);
          break;
        case 0xa402:
          meta.exposureMode = readNumbers(view, e, littleEndian)[0];
          break;
        case 0xa403:
          meta.whiteBalance = readNumbers(view, e, littleEndian)[0];
          break;
        case 0xa405:
          meta.focalLengthIn35mmFilm = readNumbers(view, e, littleEndian)[0];
          break;
        case 0xa433:
          meta.lensMake = readAscii(bytes, e.dataOffset, e.count);
          break;
        case 0xa434:
          meta.lensModel = readAscii(bytes, e.dataOffset, e.count);
          break;
      }
    }
  }

  if (gpsIfdOffset !== null) {
    const gpsIfd = readIfdEntries(view, tiffStart, gpsIfdOffset, littleEndian);
    let latRef = 'N';
    let lonRef = 'E';
    let latRats: ExifRational[] | null = null;
    let lonRats: ExifRational[] | null = null;
    let altRef = 0;
    let altRat: ExifRational | null = null;

    for (const e of gpsIfd) {
      switch (e.tag) {
        case 0x0001:
          latRef = readAscii(bytes, e.dataOffset, e.count) || 'N';
          break;
        case 0x0002:
          latRats = readRationals(view, e, littleEndian);
          break;
        case 0x0003:
          lonRef = readAscii(bytes, e.dataOffset, e.count) || 'E';
          break;
        case 0x0004:
          lonRats = readRationals(view, e, littleEndian);
          break;
        case 0x0005:
          altRef = readNumbers(view, e, littleEndian)[0] ?? 0;
          meta.altitudeRef = altRef;
          break;
        case 0x0006:
          altRat = readRationals(view, e, littleEndian)[0] ?? null;
          break;
        case 0x0007: {
          const t = readRationals(view, e, littleEndian);
          if (t.length === 3) meta.gpsTimeStamp = [t[0], t[1], t[2]];
          break;
        }
        case 0x001b:
          meta.gpsProcessingMethod = readUserComment(bytes, e.dataOffset, e.count);
          break;
        case 0x001d:
          meta.gpsDateStamp = readAscii(bytes, e.dataOffset, e.count);
          break;
      }
    }

    if (latRats && lonRats) {
      meta.latitude = dmsRationalsToDecimal(latRats, latRef);
      meta.longitude = dmsRationalsToDecimal(lonRats, lonRef);
    }
    if (altRat && altRat.denominator !== 0) {
      const meters = altRat.numerator / altRat.denominator;
      meta.altitudeMeters = altRef === 1 ? -Math.abs(meters) : meters;
    }
  }

  meta.dateTimeMillis =
    parseExifDateTimeToMillis(meta.dateTimeOriginal) ??
    parseExifDateTimeToMillis(meta.dateTimeDigitized) ??
    parseExifDateTimeToMillis(meta.dateTime);

  return meta;
}

interface OutIfdEntry {
  tag: number;
  type: number;
  count: number;
  payload: Uint8Array;
}

function encodeAsciiEntry(tag: number, value: string): OutIfdEntry {
  const clean = value.replace(/[^\x20-\x7e]/g, '?');
  const bytes = new Uint8Array(clean.length + 1);
  for (let i = 0; i < clean.length; i++) {
    bytes[i] = clean.charCodeAt(i);
  }
  bytes[clean.length] = 0;
  return { tag, type: TYPE_ASCII, count: bytes.length, payload: bytes };
}

function encodeShortEntry(tag: number, values: number[]): OutIfdEntry {
  const bytes = new Uint8Array(values.length * 2);
  const view = new DataView(bytes.buffer);
  values.forEach((v, idx) => view.setUint16(idx * 2, v & 0xffff, true));
  return { tag, type: TYPE_SHORT, count: values.length, payload: bytes };
}

function encodeLongEntry(tag: number, value: number): OutIfdEntry {
  const bytes = new Uint8Array(4);
  new DataView(bytes.buffer).setUint32(0, value >>> 0, true);
  return { tag, type: TYPE_LONG, count: 1, payload: bytes };
}

function encodeByteEntry(tag: number, values: number[]): OutIfdEntry {
  return { tag, type: TYPE_BYTE, count: values.length, payload: new Uint8Array(values) };
}

function encodeUndefinedEntry(tag: number, rawBytes: Uint8Array): OutIfdEntry {
  return { tag, type: TYPE_UNDEFINED, count: rawBytes.length, payload: rawBytes };
}

function encodeRationalEntry(
  tag: number,
  rationals: ExifRational[],
  signed = false
): OutIfdEntry {
  const bytes = new Uint8Array(rationals.length * 8);
  const view = new DataView(bytes.buffer);
  rationals.forEach((r, idx) => {
    const den = r.denominator === 0 ? 1 : r.denominator;
    if (signed) {
      view.setInt32(idx * 8, Math.trunc(r.numerator), true);
      view.setInt32(idx * 8 + 4, Math.trunc(den), true);
    } else {
      view.setUint32(idx * 8, Math.max(0, Math.round(r.numerator)) >>> 0, true);
      view.setUint32(idx * 8 + 4, Math.max(1, Math.round(den)) >>> 0, true);
    }
  });
  return {
    tag,
    type: signed ? TYPE_SRATIONAL : TYPE_RATIONAL,
    count: rationals.length,
    payload: bytes,
  };
}

function decimalToDmsRationals(coord: number): [ExifRational, ExifRational, ExifRational] {
  const abs = Math.abs(coord);
  const deg = Math.floor(abs);
  const minFloat = (abs - deg) * 60;
  const min = Math.floor(minFloat);
  const secFloat = (minFloat - min) * 60;
  const secScaled = Math.round(secFloat * 1_000_000);
  if (secScaled >= 60_000_000) {
    return [
      { numerator: deg, denominator: 1 },
      { numerator: min + 1, denominator: 1 },
      { numerator: 0, denominator: 1_000_000 },
    ];
  }
  return [
    { numerator: deg, denominator: 1 },
    { numerator: min, denominator: 1 },
    { numerator: secScaled, denominator: 1_000_000 },
  ];
}

function evenAlign(n: number): number {
  return (n + 1) & ~1;
}

function measureIfdBlockSize(entries: OutIfdEntry[]): number {
  let size = 2 + entries.length * 12 + 4;
  for (const e of entries) {
    if (e.payload.length > 4) {
      size += evenAlign(e.payload.length);
    }
  }
  return size;
}

function writeIfdBlock(
  out: Uint8Array,
  view: DataView,
  tiffStart: number,
  ifdOffset: number,
  entries: OutIfdEntry[]
): void {
  const sorted = [...entries].sort((a, b) => a.tag - b.tag);
  const tableStart = tiffStart + ifdOffset;
  view.setUint16(tableStart, sorted.length, true);
  let dataRelPtr = ifdOffset + 2 + sorted.length * 12 + 4;

  sorted.forEach((e, idx) => {
    const entryAbs = tableStart + 2 + idx * 12;
    view.setUint16(entryAbs, e.tag, true);
    view.setUint16(entryAbs + 2, e.type, true);
    view.setUint32(entryAbs + 4, e.count, true);

    if (e.payload.length <= 4) {
      out.set(e.payload, entryAbs + 8);
    } else {
      view.setUint32(entryAbs + 8, dataRelPtr, true);
      out.set(e.payload, tiffStart + dataRelPtr);
      dataRelPtr += evenAlign(e.payload.length);
    }
  });

  // Next IFD offset = 0
  view.setUint32(tableStart + 2 + sorted.length * 12, 0, true);
}

function stripApp1Segments(jpegBytes: Uint8Array): Uint8Array {
  if (jpegBytes.length < 4 || jpegBytes[0] !== 0xff || jpegBytes[1] !== 0xd8) {
    return jpegBytes;
  }

  const slices: Uint8Array[] = [];
  let offset = 2;

  while (offset < jpegBytes.length) {
    if (jpegBytes[offset] !== 0xff) {
      slices.push(jpegBytes.subarray(offset));
      break;
    }
    const marker = jpegBytes[offset + 1];
    if (marker === undefined) break;
    if (marker === 0xda || marker === 0xd9) {
      slices.push(jpegBytes.subarray(offset));
      break;
    }
    if (marker === 0xff) {
      offset++;
      continue;
    }
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
      slices.push(jpegBytes.subarray(offset, offset + 2));
      offset += 2;
      continue;
    }
    if (offset + 3 >= jpegBytes.length) {
      slices.push(jpegBytes.subarray(offset));
      break;
    }
    const segLen = (jpegBytes[offset + 2] << 8) | jpegBytes[offset + 3];
    if (segLen < 2 || offset + 2 + segLen > jpegBytes.length) {
      slices.push(jpegBytes.subarray(offset));
      break;
    }
    if (marker !== 0xe1) {
      slices.push(jpegBytes.subarray(offset, offset + 2 + segLen));
    }
    offset += 2 + segLen;
  }

  const totalBody = slices.reduce((acc, s) => acc + s.length, 0);
  const cleaned = new Uint8Array(2 + totalBody);
  cleaned[0] = 0xff;
  cleaned[1] = 0xd8;
  let cursor = 2;
  for (const s of slices) {
    cleaned.set(s, cursor);
    cursor += s.length;
  }
  return cleaned;
}

function asciiBytes(text: string): Uint8Array {
  const out = new Uint8Array(text.length);
  for (let i = 0; i < text.length; i++) {
    out[i] = text.charCodeAt(i) & 0x7f;
  }
  return out;
}

export function applyExifMetadataToJpeg(
  jpegBytes: Uint8Array,
  options: WriteExifOptions
): Uint8Array {
  if (jpegBytes.length < 4 || jpegBytes[0] !== 0xff || jpegBytes[1] !== 0xd8) {
    throw new Error('Invalid JPEG stream');
  }

  const cleanBodyJpeg = stripApp1Segments(jpegBytes);
  const orig = options.originalExif || null;

  const exifDateStr = formatExifDateTime(options.dateTimeMillis);
  const exifOffsetStr = formatExifOffset(options.dateTimeMillis);

  const hasGps =
    typeof options.latitude === 'number' &&
    typeof options.longitude === 'number' &&
    Number.isFinite(options.latitude) &&
    Number.isFinite(options.longitude);

  const ifd0Entries: OutIfdEntry[] = [
    encodeAsciiEntry(0x010e, SHAMAIL_IMAGE_DESCRIPTION),
    encodeShortEntry(0x0112, [1]),
    encodeAsciiEntry(
      0x0131,
      options.software || orig?.software || 'Gps cam by shamail v8.0'
    ),
    encodeAsciiEntry(0x0132, exifDateStr),
    encodeAsciiEntry(
      0x013b,
      options.organizationName || orig?.artist || 'SHAMAIL PERA'
    ),
  ];

  if (orig?.make) ifd0Entries.push(encodeAsciiEntry(0x010f, orig.make));
  if (orig?.model) ifd0Entries.push(encodeAsciiEntry(0x0110, orig.model));
  if (orig?.copyright) ifd0Entries.push(encodeAsciiEntry(0x8298, orig.copyright));

  const userCommentPayload = asciiBytes(`ASCII\0\0\0${SHAMAIL_USER_COMMENT}`);
  const exifIfdEntries: OutIfdEntry[] = [
    encodeUndefinedEntry(0x9000, asciiBytes('0232')),
    encodeAsciiEntry(0x9003, exifDateStr),
    encodeAsciiEntry(0x9004, exifDateStr),
    encodeAsciiEntry(0x9010, exifOffsetStr),
    encodeAsciiEntry(0x9011, exifOffsetStr),
    encodeAsciiEntry(0x9012, exifOffsetStr),
    encodeUndefinedEntry(0x9286, userCommentPayload),
    encodeShortEntry(0xa001, [1]),
  ];

  if (orig?.exposureTime) {
    exifIfdEntries.push(encodeRationalEntry(0x829a, [orig.exposureTime]));
  }
  if (orig?.fNumber) {
    exifIfdEntries.push(encodeRationalEntry(0x829d, [orig.fNumber]));
  }
  if (typeof orig?.exposureProgram === 'number') {
    exifIfdEntries.push(encodeShortEntry(0x8822, [orig.exposureProgram]));
  }
  if (orig?.isoSpeedRatings && orig.isoSpeedRatings.length > 0) {
    exifIfdEntries.push(encodeShortEntry(0x8827, orig.isoSpeedRatings));
  }
  if (orig?.shutterSpeedValue) {
    exifIfdEntries.push(encodeRationalEntry(0x9201, [orig.shutterSpeedValue], true));
  }
  if (orig?.apertureValue) {
    exifIfdEntries.push(encodeRationalEntry(0x9202, [orig.apertureValue]));
  }
  if (orig?.exposureBiasValue) {
    exifIfdEntries.push(encodeRationalEntry(0x9204, [orig.exposureBiasValue], true));
  }
  if (typeof orig?.flash === 'number') {
    exifIfdEntries.push(encodeShortEntry(0x9209, [orig.flash]));
  }
  if (orig?.focalLength) {
    exifIfdEntries.push(encodeRationalEntry(0x920a, [orig.focalLength]));
  }
  if (typeof orig?.exposureMode === 'number') {
    exifIfdEntries.push(encodeShortEntry(0xa402, [orig.exposureMode]));
  }
  if (typeof orig?.whiteBalance === 'number') {
    exifIfdEntries.push(encodeShortEntry(0xa403, [orig.whiteBalance]));
  }
  if (typeof orig?.focalLengthIn35mmFilm === 'number') {
    exifIfdEntries.push(encodeShortEntry(0xa405, [orig.focalLengthIn35mmFilm]));
  }
  if (orig?.lensMake) {
    exifIfdEntries.push(encodeAsciiEntry(0xa433, orig.lensMake));
  }
  if (orig?.lensModel) {
    exifIfdEntries.push(encodeAsciiEntry(0xa434, orig.lensModel));
  }

  const gpsIfdEntries: OutIfdEntry[] = [];
  if (hasGps) {
    const lat = options.latitude!;
    const lon = options.longitude!;
    const utcDate = new Date(options.dateTimeMillis);
    const gpsDateStamp = `${utcDate.getUTCFullYear()}:${pad2(
      utcDate.getUTCMonth() + 1
    )}:${pad2(utcDate.getUTCDate())}`;

    gpsIfdEntries.push(
      encodeByteEntry(0x0000, [2, 3, 0, 0]),
      encodeAsciiEntry(0x0001, lat >= 0 ? 'N' : 'S'),
      encodeRationalEntry(0x0002, decimalToDmsRationals(lat)),
      encodeAsciiEntry(0x0003, lon >= 0 ? 'E' : 'W'),
      encodeRationalEntry(0x0004, decimalToDmsRationals(lon)),
      encodeRationalEntry(0x0007, [
        { numerator: utcDate.getUTCHours(), denominator: 1 },
        { numerator: utcDate.getUTCMinutes(), denominator: 1 },
        { numerator: utcDate.getUTCSeconds(), denominator: 1 },
      ]),
      encodeAsciiEntry(0x001d, gpsDateStamp)
    );

    const altMeters = options.altitudeMeters ?? orig?.altitudeMeters;
    if (typeof altMeters === 'number' && Number.isFinite(altMeters)) {
      const altRef =
        typeof options.altitudeRef === 'number'
          ? options.altitudeRef
          : typeof orig?.altitudeRef === 'number'
            ? orig.altitudeRef
            : altMeters < 0
              ? 1
              : 0;
      gpsIfdEntries.push(
        encodeByteEntry(0x0005, [altRef]),
        encodeRationalEntry(0x0006, [
          { numerator: Math.round(Math.abs(altMeters) * 100), denominator: 100 },
        ])
      );
    }

    const procMethod = options.gpsProcessingMethod || orig?.gpsProcessingMethod;
    if (procMethod) {
      gpsIfdEntries.push(encodeUndefinedEntry(0x001b, asciiBytes(`ASCII\0\0\0${procMethod}`)));
    }
  }

  // Reserve pointers in IFD0 BEFORE measuring IFD0 size
  ifd0Entries.push(encodeLongEntry(0x8769, 0));
  if (hasGps) {
    ifd0Entries.push(encodeLongEntry(0x8825, 0));
  }

  const ifd0Offset = 8;
  const ifd0Size = measureIfdBlockSize(ifd0Entries);
  const exifIfdOffset = ifd0Offset + ifd0Size;
  const exifIfdSize = measureIfdBlockSize(exifIfdEntries);
  const gpsIfdOffset = exifIfdOffset + exifIfdSize;
  const gpsIfdSize = hasGps ? measureIfdBlockSize(gpsIfdEntries) : 0;

  // Fill exact pointer offsets
  const exifPtrEntry = ifd0Entries.find((e) => e.tag === 0x8769)!;
  new DataView(exifPtrEntry.payload.buffer).setUint32(0, exifIfdOffset, true);
  if (hasGps) {
    const gpsPtrEntry = ifd0Entries.find((e) => e.tag === 0x8825)!;
    new DataView(gpsPtrEntry.payload.buffer).setUint32(0, gpsIfdOffset, true);
  }

  const tiffTotalSize = 8 + ifd0Size + exifIfdSize + gpsIfdSize;
  const app1PayloadSize = 6 + tiffTotalSize; // "Exif\0\0" + TIFF
  const app1SegmentLen = 2 + app1PayloadSize; // 2 length bytes + payload
  const app1TotalBytes = 2 + app1SegmentLen; // FF E1 + length + payload

  const finalBytes = new Uint8Array(2 + app1TotalBytes + (cleanBodyJpeg.length - 2));
  const outView = new DataView(finalBytes.buffer);

  // SOI
  finalBytes[0] = 0xff;
  finalBytes[1] = 0xd8;

  // APP1 header
  finalBytes[2] = 0xff;
  finalBytes[3] = 0xe1;
  outView.setUint16(4, app1SegmentLen, false);
  finalBytes.set([0x45, 0x78, 0x69, 0x66, 0x00, 0x00], 6);

  // TIFF Header at offset 12
  const tiffStart = 12;
  finalBytes[tiffStart] = 0x49;
  finalBytes[tiffStart + 1] = 0x49;
  outView.setUint16(tiffStart + 2, 0x002a, true);
  outView.setUint32(tiffStart + 4, ifd0Offset, true);

  writeIfdBlock(finalBytes, outView, tiffStart, ifd0Offset, ifd0Entries);
  writeIfdBlock(finalBytes, outView, tiffStart, exifIfdOffset, exifIfdEntries);
  if (hasGps) {
    writeIfdBlock(finalBytes, outView, tiffStart, gpsIfdOffset, gpsIfdEntries);
  }

  // Remaining JPEG body after FF D8
  finalBytes.set(cleanBodyJpeg.subarray(2), 2 + app1TotalBytes);
  return finalBytes;
}
