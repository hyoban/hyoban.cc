export { compareMoments } from '@hyoban/trip-map'

/** The `NN` in a `YYYY/MM/DD-NN-slug` Moment id. */
export function getMomentOrder(id: string, dateKey: string) {
  const directory = id.split('/').at(-1)
  const match = directory?.match(/^(\d{2})-(\d{2})-[a-z0-9]+(?:-[a-z0-9]+)*$/)
  const expectedDay = dateKey.slice(8, 10)

  if (!match || match[1] !== expectedDay) {
    throw new Error(`Invalid Moment directory id: ${id}`)
  }

  return Number.parseInt(match[2]!, 10)
}
