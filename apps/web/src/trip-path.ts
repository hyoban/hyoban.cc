// A trip's address on the site: its year and the rest of its id, /trip/2026/xuzhou-zhoumo-xing/,
// which the map also reads by the id itself (/trip/2026-xuzhou-zhoumo-xing/); playing, from
// its start or from the card of a stop.

export function getTripPath(id: string, play?: { stop?: string | undefined }) {
  const [, year, rest] = id.match(/^(\d{4})-(.+)$/) ?? []
  const trip = year && rest ? `${year}/${encodeURIComponent(rest)}` : encodeURIComponent(id)
  const stop = play?.stop ? `${encodeURIComponent(play.stop)}/` : ''
  return `/trip/${trip}/${play ? `play/${stop}` : ''}`
}
