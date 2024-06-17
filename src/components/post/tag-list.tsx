export function TagList({
  tags,
}: {
  tags: string[]
}) {
  return (
    <section className="flex items-center gap-2">
      {tags.map(tag => (
        <span
          key={tag}
          className="border border-gray-4 rounded-md px-1 py-0.5 text-sm"
        >
          {tag}
        </span>
      ))}
    </section>
  )
}
