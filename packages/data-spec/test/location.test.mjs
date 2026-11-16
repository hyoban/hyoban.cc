import assert from 'node:assert/strict'
import sharp from 'sharp'
import { test } from 'vite-plus/test'

import { findLocation, hasLocation, stripLocation } from '../src/location.ts'

// A made-up place, nowhere the owner has been.
const GPS = {
  GPSLatitudeRef: 'N',
  GPSLatitude: '12/1 34/1 5600/100',
  GPSLongitudeRef: 'E',
  GPSLongitude: '98/1 7/1 6500/100',
  GPSAltitudeRef: '0',
  GPSAltitude: '120/1',
}
const EXIF = { IFD0: { Make: 'Test', Model: 'Camera' }, IFD3: GPS }

async function image(format) {
  return new Uint8Array(
    await sharp({ create: { width: 8, height: 6, channels: 3, background: '#38c' } })
      .withExif(EXIF)
      [format]()
      .toBuffer(),
  )
}

/** What sharp reads of a file's EXIF: its orientation, whether it names the camera, and whether it holds the latitude. */
async function read(file) {
  const { exif, orientation } = await sharp(file).metadata()
  const latitude = (little) => {
    const bytes = Buffer.alloc(16)
    for (const [index, value] of [12, 1, 34, 1].entries()) {
      if (little) bytes.writeUInt32LE(value, index * 4)
      else bytes.writeUInt32BE(value, index * 4)
    }
    return bytes
  }

  return {
    orientation,
    model: exif?.includes('Camera') ?? false,
    gps: Boolean(exif && (exif.includes(latitude(true)) || exif.includes(latitude(false)))),
  }
}

for (const format of ['jpeg', 'png', 'webp', 'avif']) {
  test(`blanks the GPS of a ${format}'s EXIF in place, keeping the rest`, async () => {
    const before = await image(format)
    const after = before.slice()

    assert.ok(hasLocation(before))
    assert.equal((await read(before)).gps, true)
    assert.ok(stripLocation(after) > 0)
    assert.equal(after.length, before.length)
    assert.deepEqual(findLocation(after), [])
    assert.equal(stripLocation(after), 0, 'a second pass finds nothing')

    const [pixelsBefore, pixelsAfter] = await Promise.all(
      [before, after].map((file) => sharp(file).raw().toBuffer()),
    )
    assert.ok(pixelsBefore.equals(pixelsAfter))
    assert.deepEqual(await read(after), { ...(await read(before)), gps: false })
  })
}

test('leaves a file that says nothing of where it was alone', async () => {
  const file = new Uint8Array(
    await sharp({ create: { width: 4, height: 4, channels: 3, background: '#000' } })
      .withExif({ IFD0: { Model: 'Camera' } })
      .jpeg()
      .toBuffer(),
  )

  assert.deepEqual(findLocation(file), [])
  assert.equal(hasLocation(new Uint8Array([0xff, 0xd8, 0xff, 0xd9])), false)
})

test('blanks the GPS of an XMP packet, as attributes and as elements', () => {
  const xmp =
    '<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:Description exif:GPSLatitude="12,34.93N" ' +
    'exif:GPSLongitude=\'98,7.1E\' exif:DateTimeOriginal="2020">' +
    '<exif:GPSAltitude>120/1</exif:GPSAltitude></rdf:Description></x:xmpmeta>'
  const file = new TextEncoder().encode(`xx${xmp}yy`)

  stripLocation(file)
  const text = new TextDecoder().decode(file)

  assert.equal(text.length, xmp.length + 4)
  assert.match(text, /exif:GPSLatitude=" {9}"/)
  assert.match(text, /exif:GPSLongitude=' {7}'/)
  assert.match(text, /<exif:GPSAltitude> {5}<\/exif:GPSAltitude>/)
  assert.match(text, /exif:DateTimeOriginal="2020"/)
  assert.equal(hasLocation(file), false)
})

function box(type, ...children) {
  const body = concat(...children)
  const header = new Uint8Array(8)
  new DataView(header.buffer).setUint32(0, 8 + body.length)
  header.set(latin1(type), 4)
  return concat(header, body)
}

function latin1(text) {
  return Uint8Array.from(text, (character) => character.charCodeAt(0))
}

function concat(...parts) {
  const all = new Uint8Array(parts.reduce((length, part) => length + part.length, 0))
  let at = 0
  for (const part of parts) {
    all.set(part, at)
    at += part.length
  }
  return all
}

function uint32(value) {
  const bytes = new Uint8Array(4)
  new DataView(bytes.buffer).setUint32(0, value)
  return bytes
}

test("turns an MP4's location boxes into free space and zeroes keyed locations", () => {
  const where = latin1('+12.5823+098.1181/')
  const key = latin1('com.apple.quicktime.location.ISO6709')
  const video = concat(
    box('ftyp', latin1('isom'), uint32(0)),
    box(
      'moov',
      box('udta', box('©xyz', uint32(0), where), box('loci', where)),
      box(
        'meta',
        box('hdlr', new Uint8Array(12)),
        box(
          'keys',
          uint32(0),
          uint32(2),
          concat(uint32(8 + key.length), latin1('mdta'), key),
          concat(uint32(12), latin1('mdtatest')),
        ),
        box(
          'ilst',
          box('\0\0\0\u0001', box('data', uint32(1), uint32(0), where)),
          box('\0\0\0\u0002', box('data', uint32(1), uint32(0), latin1('kept'))),
        ),
      ),
    ),
    box('mdat', latin1('frames')),
  )
  const before = video.slice()

  assert.ok(stripLocation(video) > 0)
  assert.equal(video.length, before.length)
  assert.equal(hasLocation(video), false)

  const text = new TextDecoder('latin1').decode(video)
  assert.ok(!text.includes('+12.5823'))
  assert.ok(!text.includes('©xyz') && !text.includes('loci'))
  assert.equal(text.split('free').length - 1, 2)
  assert.ok(text.includes('kept') && text.includes('frames'))
})

test('finds a location in a video a motion photo carries after its still', async () => {
  const still = new Uint8Array(
    await sharp({ create: { width: 4, height: 4, channels: 3, background: '#000' } })
      .jpeg()
      .toBuffer(),
  )
  const motion = concat(
    box('ftyp', latin1('mp42'), uint32(0)),
    box('moov', box('udta', box('©xyz', uint32(0), latin1('+12.5+098.1/')))),
  )
  const file = concat(still, motion)

  assert.ok(hasLocation(file))
  stripLocation(file)
  assert.equal(hasLocation(file), false)
  assert.ok((await sharp(file).raw().toBuffer()).length > 0)
})
