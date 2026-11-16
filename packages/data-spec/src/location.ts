// Where a photo or video was taken, as its file says it, taken out before it is published:
// the GPS of its EXIF (JPEG, HEIC and AVIF, WebP, PNG), the coordinates of its XMP, and the
// location boxes of an MP4 or QuickTime file, or of one a motion photo carries after its
// still. Each is blanked where it is, so nothing moves: the file stays the size it was and
// its pixels, orientation and the rest of its metadata stay as they were. The phone does
// the same before it uploads (the shared Kotlin `Location.kt`), and the API turns down an
// image that still says where it was.

/** Bytes to write over a file at an offset, to blank one record of where it was taken. */
export interface LocationEdit {
  at: number
  bytes: Uint8Array
}

/** The edits that blank everything in `file` that says where it was taken; none when nothing does. */
export function findLocation(file: Uint8Array): LocationEdit[] {
  return [...findExifGps(file), ...findXmpGps(file), ...findBoxLocations(file)]
}

/** Blanks, in place, everything in `file` that says where it was taken; how many records it blanked. */
export function stripLocation(file: Uint8Array) {
  const edits = findLocation(file)

  for (const { at, bytes } of edits) {
    file.set(bytes, at)
  }

  return edits.length
}

/** Whether anything in `file` says where it was taken. */
export function hasLocation(file: Uint8Array) {
  return findLocation(file).length > 0
}

const EXIF = ascii('Exif\0\0')
const TIFF_LITTLE = ascii('II*\0')
const TIFF_BIG = ascii('MM\0*')
const PNG_EXIF = ascii('eXIf')
const WEBP_EXIF = ascii('EXIF')
const GPS_IFD = 0x8825
/** Bytes per value of each TIFF field type, by its number. */
const TYPE_SIZES = [0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4]

/**
 * The EXIF blocks: after `Exif\0\0` in a JPEG's APP1, a HEIC's Exif item or a WebP's chunk,
 * or straight after a PNG's eXIf chunk type or a WebP's EXIF chunk header. In each, the GPS
 * IFD's values and then the IFD itself are zeroed, which leaves an IFD of no fields that
 * the pointer to it still finds.
 */
function findExifGps(file: Uint8Array) {
  const starts = new Set<number>()

  for (const at of indicesOf(file, EXIF)) starts.add(at + EXIF.length)
  for (const at of indicesOf(file, PNG_EXIF)) starts.add(at + PNG_EXIF.length)
  for (const at of indicesOf(file, WEBP_EXIF)) starts.add(at + WEBP_EXIF.length + 4)

  return [...starts]
    .filter((start) => isAt(file, TIFF_LITTLE, start) || isAt(file, TIFF_BIG, start))
    .flatMap((start) => findTiffGps(file, start))
}

function findTiffGps(file: Uint8Array, tiff: number): LocationEdit[] {
  const view = new DataView(file.buffer, file.byteOffset, file.byteLength)
  const little = file[tiff] === 0x49
  const inside = (at: number, length: number) => at >= 0 && at + length <= file.length
  const u16 = (at: number) => view.getUint16(at, little)
  const u32 = (at: number) => view.getUint32(at, little)

  if (!inside(tiff, 8)) return []

  const ifd0 = tiff + u32(tiff + 4)

  if (!inside(ifd0, 2)) return []

  const fields = u16(ifd0)

  if (!inside(ifd0 + 2, fields * 12)) return []

  for (let index = 0; index < fields; index += 1) {
    const field = ifd0 + 2 + index * 12

    if (u16(field) !== GPS_IFD) continue

    const gps = tiff + u32(field + 8)

    if (!inside(gps, 2)) return []

    const count = u16(gps)
    const length = 2 + count * 12 + 4

    // Already blank, or not an IFD this file holds.
    if (count === 0 || !inside(gps, length)) return []

    const edits: LocationEdit[] = []

    for (let entry = 0; entry < count; entry += 1) {
      const at = gps + 2 + entry * 12
      const size = (TYPE_SIZES[u16(at + 2)] ?? 1) * u32(at + 4)
      const value = tiff + u32(at + 8)

      // A value of more than four bytes is kept elsewhere, at the offset the field gives.
      if (size > 4 && inside(value, size)) {
        edits.push({ at: value, bytes: new Uint8Array(size) })
      }
    }

    return [...edits, { at: gps, bytes: new Uint8Array(length) }]
  }

  return []
}

const XMP_START = ascii('<x:xmpmeta')
const XMP_END = ascii('</x:xmpmeta>')
/** An `exif:GPS…` property's value, as an attribute in either quotes or as an element. */
const XMP_GPS = [
  /\bexif:GPS[A-Za-z]*\s*=\s*"(?<value>[^"]*)"/dg,
  /\bexif:GPS[A-Za-z]*\s*=\s*'(?<value>[^']*)'/dg,
  /<exif:(?<name>GPS[A-Za-z]*)\b[^>]*>(?<value>[^<]*)<\/exif:\k<name>>/dg,
]

/**
 * The XMP packets' `exif:GPS…` properties, as attributes or elements: their values become
 * spaces of the same length, which keeps the packet well formed and its size the same.
 */
function findXmpGps(file: Uint8Array) {
  const edits: LocationEdit[] = []

  for (const start of indicesOf(file, XMP_START)) {
    const end = indexOf(file, XMP_END, start)

    if (end < 0) continue

    const packet = latin1(file.subarray(start, end))

    for (const pattern of XMP_GPS) {
      for (const match of packet.matchAll(pattern)) {
        const [from, to] = match.indices!.groups!['value']!

        if (packet.slice(from, to).trim() !== '') {
          edits.push({ at: start + from, bytes: new Uint8Array(to - from).fill(0x20) })
        }
      }
    }
  }

  return edits
}

const FTYP = ascii('ftyp')
const FREE = ascii('free')
/** The boxes that hold others on the way to a location: QuickTime's and ISO's metadata. */
const CONTAINERS = new Set(['moov', 'trak', 'udta', 'meta'])
/** The boxes that are only a location: QuickTime's `©xyz` and 3GPP's `loci`. */
const LOCATIONS = new Set(['©xyz', 'loci'])
/** The metadata keys Apple's cameras keep a location under. */
const LOCATION_KEYS = new Set([
  'com.apple.quicktime.location.ISO6709',
  'com.apple.quicktime.location.body',
  'com.apple.quicktime.location.name',
  'com.apple.quicktime.location.note',
  'com.apple.quicktime.location.role',
  'com.apple.quicktime.location.date',
])

/**
 * The location boxes of every ISO-BMFF file in `file` (itself, as an MP4, a QuickTime file
 * or a HEIC, or a video a motion photo carries after its still), found from each `ftyp`:
 * a location box becomes a `free` box of zeros, and a metadata item under a location key
 * has its value zeroed.
 */
function findBoxLocations(file: Uint8Array) {
  return indicesOf(file, FTYP)
    .map((at) => at - 4)
    .filter((start) => start >= 0 && readUint32(file, start) >= 8)
    .flatMap((start) => findInBoxes(file, start, file.length))
}

function findInBoxes(file: Uint8Array, start: number, end: number): LocationEdit[] {
  const edits: LocationEdit[] = []
  let at = start
  let keys: string[] | undefined
  let items: { start: number; end: number } | undefined

  while (at + 8 <= end) {
    const header = readBox(file, at, end)

    if (!header) break

    const { type, body, next } = header

    if (LOCATIONS.has(type)) {
      edits.push({ at: at + 4, bytes: FREE }, { at: body, bytes: new Uint8Array(next - body) })
    } else if (type === 'meta') {
      // ISO's meta is a full box, its children after a version and flags; QuickTime's isn't.
      const children = readUint32(file, body) === 0 ? body + 4 : body
      edits.push(...findInBoxes(file, children, next))
    } else if (CONTAINERS.has(type)) {
      edits.push(...findInBoxes(file, body, next))
    } else if (type === 'keys') {
      keys = readKeys(file, body, next)
    } else if (type === 'ilst') {
      items = { start: body, end: next }
    }

    at = next
  }

  if (keys && items) {
    edits.push(...findKeyedLocations(file, keys, items))
  }

  return edits
}

/** A box's type, where its contents start and where the next box starts; undefined when it doesn't fit. */
function readBox(file: Uint8Array, at: number, end: number) {
  let size = readUint32(file, at)
  let body = at + 8

  if (size === 1) {
    if (at + 16 > end) return undefined
    size = Number(new DataView(file.buffer, file.byteOffset).getBigUint64(at + 8))
    body = at + 16
  } else if (size === 0) {
    size = end - at
  }

  const next = at + size

  if (size < body - at || next > end) return undefined

  return { type: latin1(file.subarray(at + 4, at + 8)), body, next }
}

/** The names of a QuickTime `keys` box, by their index from 1 (the first is at 0). */
function readKeys(file: Uint8Array, body: number, end: number) {
  const count = readUint32(file, body + 4)
  const keys: string[] = []
  let at = body + 8

  for (let index = 0; index < count && at + 8 <= end; index += 1) {
    const size = readUint32(file, at)

    if (size < 8 || at + size > end) break

    keys.push(latin1(file.subarray(at + 8, at + size)))
    at += size
  }

  return keys
}

/** The `data` boxes' values of the `ilst` items under a location key, zeroed. */
function findKeyedLocations(
  file: Uint8Array,
  keys: string[],
  items: { start: number; end: number },
) {
  const edits: LocationEdit[] = []
  let at = items.start

  while (at + 8 <= items.end) {
    const item = readBox(file, at, items.end)

    if (!item) break

    const key = keys[readUint32(file, at + 4) - 1]

    if (key && LOCATION_KEYS.has(key)) {
      let child = item.body

      while (child + 8 <= item.next) {
        const data = readBox(file, child, item.next)

        if (!data) break

        // A data box's value comes after its type and locale; one already zeroed is left.
        if (
          data.type === 'data' &&
          file.subarray(data.body + 8, data.next).some((byte) => byte !== 0)
        ) {
          edits.push({ at: data.body + 8, bytes: new Uint8Array(data.next - data.body - 8) })
        }

        child = data.next
      }
    }

    at = item.next
  }

  return edits
}

function readUint32(file: Uint8Array, at: number) {
  return at + 4 <= file.length
    ? new DataView(file.buffer, file.byteOffset, file.byteLength).getUint32(at)
    : 0
}

function ascii(text: string) {
  return Uint8Array.from(text, (character) => character.charCodeAt(0))
}

function latin1(bytes: Uint8Array) {
  let text = ''
  for (const byte of bytes) text += String.fromCharCode(byte)
  return text
}

function isAt(file: Uint8Array, pattern: Uint8Array, at: number) {
  if (at < 0 || at + pattern.length > file.length) return false

  for (let index = 0; index < pattern.length; index += 1) {
    if (file[at + index] !== pattern[index]) return false
  }

  return true
}

function indexOf(file: Uint8Array, pattern: Uint8Array, from = 0) {
  const first = pattern[0]!

  for (let at = file.indexOf(first, from); at >= 0; at = file.indexOf(first, at + 1)) {
    if (isAt(file, pattern, at)) return at
  }

  return -1
}

function indicesOf(file: Uint8Array, pattern: Uint8Array) {
  const found: number[] = []

  for (let at = indexOf(file, pattern); at >= 0; at = indexOf(file, pattern, at + 1)) {
    found.push(at)
  }

  return found
}
