type OrderedMoment = Readonly<{
  dateKey: string
  occurredAt: string
  order: number
}>

/** Oldest first; timed Moments on the same day sort by time, then by their directory order. */
export function compareMoments(first: OrderedMoment, second: OrderedMoment) {
  const dateComparison = first.dateKey.localeCompare(second.dateKey)

  if (dateComparison !== 0) {
    return dateComparison
  }

  const firstHasTime = first.occurredAt.length > 10
  const secondHasTime = second.occurredAt.length > 10

  if (firstHasTime && secondHasTime) {
    return (
      Date.parse(first.occurredAt) - Date.parse(second.occurredAt) || first.order - second.order
    )
  }

  return first.order - second.order
}

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
