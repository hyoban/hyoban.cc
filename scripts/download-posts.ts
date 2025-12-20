import { mkdir, writeFile } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { Client } from 'sakuin'

const __dirname = dirname(fileURLToPath(import.meta.url))
const HANDLE = 'hyoban'
const OUTPUT_DIR = resolve(__dirname, '../src/content/posts')

async function downloadPosts() {
  const client = new Client()

  console.log(`Fetching posts for handle: ${HANDLE}`)
  const posts = await client.post.getAll(HANDLE)
  console.log(`Found ${posts.length} posts`)

  // Create output directory
  await mkdir(OUTPUT_DIR, { recursive: true })

  for (const post of posts) {
    const frontmatter = {
      title: post.title,
      link: post.slug,
      description: post.summary,
      pubDate: post.publishedAt,
    }

    const frontmatterYaml = Object.entries(frontmatter)
      .filter(([_, value]) => value !== undefined && value !== '' && (!Array.isArray(value) || value.length > 0))
      .map(([key, value]) => {
        if (Array.isArray(value)) {
          return `${key}:\n${value.map(v => `  - ${v}`).join('\n')}`
        }
        if (typeof value === 'string' && (value.includes(':') || value.includes('"') || value.includes('\n'))) {
          return `${key}: "${value.replace(/"/g, '\\"')}"`
        }
        return `${key}: ${value}`
      })
      .join('\n')

    const markdown = `---\n${frontmatterYaml}\n---\n\n${post.content}`

    const filename = `${post.slug}.md`
    const filepath = join(OUTPUT_DIR, filename)

    await writeFile(filepath, markdown, 'utf-8')
    console.log(`Saved: ${filename}`)
  }

  console.log(`\nDone! Downloaded ${posts.length} posts to ${OUTPUT_DIR}`)
}

downloadPosts().catch(console.error)
