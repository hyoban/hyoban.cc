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
          className="border rounded-sm px-1 py-0.5 bg-neutral-50 dark:bg-neutral-950 text-sm"
        >
          {tag}
        </span>
      ))}
    </section>
  )
}
