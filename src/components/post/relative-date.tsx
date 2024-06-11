import { formatDistance } from 'date-fns'

export function RelativeDate({ date }: { date: string }) {
  return (
    <span
      title={new Date(date).toLocaleString()}
    >
      {formatDistance(
        new Date(date),
        new Date(),
        { addSuffix: true },
      )}
    </span>
  )
}
