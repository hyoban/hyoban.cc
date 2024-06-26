import { ListItem } from '~/app/list-item'
import { capitalize, getSuperscript } from '~/app/utils'
import { client } from '~/lib/client'
import { env } from '~/lib/env'

export default async function HomePage() {
  const portfolios = await client.portfolio.getAll(env.HANDLE)

  return (
    <>
      {portfolios.map(portfolio => (
        <ListItem
          key={portfolio.noteId}
          title={capitalize(portfolio.title)}
          description={portfolio.summary}
          link={portfolio.link}
          superscript={getSuperscript(portfolio)}
        />
      ))}
    </>
  )
}
