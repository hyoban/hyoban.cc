import { AppLink } from '../components/app-link'

export function ListItem({
  title,
  description,
  superscript,
  link,
}: {
  title: string
  description: string
  superscript?: string
  link: string
}) {
  return (
    <AppLink
      href={link}
      className="not-prose -mx-3 p-3 my-2 flex flex-col rounded-md hover:bg-panel-solid"
    >
      <div className="flex gap-2">
        <span>{title}</span>
        <span className="opacity-70 text-xs shrink-0">{superscript}</span>
      </div>
      <span className="opacity-70 mt-1">{description}</span>
    </AppLink>
  )
}
