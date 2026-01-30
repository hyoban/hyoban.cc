import { mkdir, readdir, readFile, writeFile } from 'node:fs/promises'
import { basename, dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const POSTS_DIR = resolve(__dirname, '../src/content/posts')
const IMAGES_DIR = resolve(__dirname, '../src/assets/images/posts')

// Match image URLs in markdown: ![alt](url) or [![alt](url)](link)
const IMAGE_URL_REGEX = /!\[([^\]]*)\]\((https?:\/\/[^)\s]+\.(?:png|jpg|jpeg|gif|webp|svg|avif))\)/gi

interface ImageInfo {
  file: string
  alt: string
  url: string
  localPath: string
}

async function extractImageUrls(): Promise<ImageInfo[]> {
  const files = await readdir(POSTS_DIR)
  const mdFiles = files.filter(f => f.endsWith('.md'))
  const images: ImageInfo[] = []

  for (const file of mdFiles) {
    const filepath = join(POSTS_DIR, file)
    const content = await readFile(filepath, 'utf-8')

    for (const match of content.matchAll(IMAGE_URL_REGEX)) {
      const alt = match[1] ?? ''
      const url = match[2]
      if (!url)
        continue
      const filename = basename(new URL(url).pathname)
      const localPath = `../../assets/images/posts/${filename}`

      images.push({ file, alt, url, localPath })
    }
  }

  return images
}

async function downloadImage(url: string, destPath: string): Promise<boolean> {
  try {
    const response = await fetch(url, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36',
      },
    })

    if (!response.ok) {
      console.error(`Failed to download ${url}: ${response.status}`)
      return false
    }

    const buffer = new Uint8Array(await response.arrayBuffer())
    await writeFile(destPath, buffer)
    return true
  }
  catch (error) {
    console.error(`Error downloading ${url}:`, error)
    return false
  }
}

async function updateMarkdownFile(file: string, replacements: Map<string, string>) {
  const filepath = join(POSTS_DIR, file)
  let content = await readFile(filepath, 'utf-8')

  for (const [url, localPath] of replacements) {
    content = content.replaceAll(url, localPath)
  }

  await writeFile(filepath, content, 'utf-8')
}

async function main() {
  console.log('Extracting image URLs from markdown files...')
  const images = await extractImageUrls()

  console.log(`Found ${images.length} external images`)

  if (images.length === 0) {
    console.log('No external images to download')
    return
  }

  // Create images directory
  await mkdir(IMAGES_DIR, { recursive: true })

  // Group by file for later updates
  const fileReplacements = new Map<string, Map<string, string>>()

  // Download images
  let downloaded = 0
  let failed = 0

  for (const image of images) {
    const filename = basename(new URL(image.url).pathname)
    const destPath = join(IMAGES_DIR, filename)

    console.log(`Downloading: ${filename}`)
    const success = await downloadImage(image.url, destPath)

    if (success) {
      downloaded++

      if (!fileReplacements.has(image.file)) {
        fileReplacements.set(image.file, new Map())
      }
      fileReplacements.get(image.file)!.set(image.url, image.localPath)
    }
    else {
      failed++
    }
  }

  console.log(`\nDownloaded: ${downloaded}, Failed: ${failed}`)

  // Update markdown files
  console.log('\nUpdating markdown files...')
  for (const [file, replacements] of fileReplacements) {
    await updateMarkdownFile(file, replacements)
    console.log(`Updated: ${file}`)
  }

  console.log('\nDone!')
}

main().catch(console.error)
