import { randomBytes } from 'node:crypto'

// ULIDs (https://github.com/ulid/spec) name the records under data/: 48 bits of time
// and 80 random bits in Crockford base32, so they sort by time and can be made anywhere.

const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'

/** A new ULID for a moment in time, now by default. */
export function ulid(time = Date.now()) {
  if (!Number.isInteger(time) || time < 0 || time > 2 ** 48 - 1) {
    throw new RangeError(`Invalid ULID time: ${time}`)
  }

  let encoded = ''
  let remaining = time

  for (let index = 0; index < 10; index += 1) {
    encoded = ALPHABET[remaining % 32] + encoded
    remaining = Math.floor(remaining / 32)
  }

  let random = 0n

  for (const byte of randomBytes(10)) {
    random = (random << 8n) | BigInt(byte)
  }

  let randomPart = ''

  for (let index = 0; index < 16; index += 1) {
    randomPart = ALPHABET[Number(random & 31n)] + randomPart
    random >>= 5n
  }

  return encoded + randomPart
}

/** The time a ULID was made for, in milliseconds since the epoch. */
export function getUlidTime(id) {
  let time = 0

  for (const character of id.slice(0, 10)) {
    const value = ALPHABET.indexOf(character)

    if (value === -1) {
      throw new RangeError(`Invalid ULID: ${id}`)
    }

    time = time * 32 + value
  }

  return time
}
