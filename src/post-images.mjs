import assetConfig from './data/asset-config.json' with { type: 'json' }
import postImages from './data/post-images.json' with { type: 'json' }

const POST_ASSET_PREFIX = `${assetConfig.origin}/posts/`
// Articles sit in a 40rem column inside the body's 1rem side padding.
const POST_IMAGE_SIZES = '(min-width: 42rem) 40rem, calc(100vw - 2rem)'
// An image this close to the top is likely the first thing a reader sees.
const ABOVE_THE_FOLD_BLOCKS = 3

/**
 * Gives the R2 images in articles the webp variants from `pnpm posts:variants`,
 * their intrinsic size, and a loading priority based on where they sit.
 */
export function rehypePostImages() {
  return (tree) => {
    const blocks = tree.children.filter((node) => node.type === 'element')
    let isFirstImage = true

    for (const [index, block] of blocks.entries()) {
      for (const image of findImages(block)) {
        const src = image.properties.src

        if (typeof src !== 'string' || !src.startsWith(POST_ASSET_PREFIX)) {
          continue
        }

        const entry = postImages[decodeURIComponent(src.slice(assetConfig.origin.length + 1))]

        if (!entry) {
          throw new Error(`Missing ${src} in src/data/post-images.json; run pnpm posts:variants.`)
        }

        const priority = isFirstImage && index < ABOVE_THE_FOLD_BLOCKS
        isFirstImage = false

        image.properties.width = entry.width
        image.properties.height = entry.height
        image.properties.decoding = 'async'
        image.properties.loading = priority ? 'eager' : 'lazy'

        if (priority) {
          image.properties.fetchpriority = 'high'
        }

        if (entry.variants.length > 0) {
          image.properties.src = `${assetConfig.origin}/${entry.variants.at(-1).key}`
        }

        if (entry.variants.length > 1) {
          image.properties.srcSet = entry.variants
            .map((variant) => `${assetConfig.origin}/${variant.key} ${variant.width}w`)
            .join(', ')
          image.properties.sizes = POST_IMAGE_SIZES
        }
      }
    }
  }
}

function* findImages(node) {
  if (node.type === 'element' && node.tagName === 'img') {
    yield node
  }

  for (const child of node.children ?? []) {
    yield* findImages(child)
  }
}
