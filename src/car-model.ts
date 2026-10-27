import { getAssetUrl } from './asset-url.ts'

// The car in 3D: a bought model of the 007GT painted in its colour, in site/ on the image
// origin, drawn by src/car-model-client.ts once it nears the screen, where it drives in
// with the scroll the first time it's seen. It comes first in a lighter cut, and the full
// one takes its place once it has settled. With the script its place stays empty until the
// model has drawn; without it, or when the model can't be drawn, a still of it stands in,
// which is also what a feed shows.
//
// The year review draws it at the head of its driving section (src/components/CarModel.astro),
// and an article where a paragraph is nothing but the still, `![label](still)`. Held down it
// speeds off after GameMCU's Xiaomi SU7 showroom, which an article credits under the car.

const SU7_SHOWROOM = 'https://gamemcu.com/su7/'

/** The line crediting the showroom the speeding car is after. */
export const CAR_CREDIT = `<p class="car-model-credit">致谢 <a href="${SU7_SHOWROOM}" target="_blank" rel="noopener noreferrer">GameMCU 的 Xiaomi SU7 展厅</a></p>`

/** The lighter cut, also driving a trip's route while its playback flies over. */
export const CAR_MODEL = getAssetUrl('site/zeekr-007gt-9.glb')
export const CAR_MODEL_FULL = getAssetUrl('site/zeekr-007gt-9-full.glb')
export const CAR_STILL = { src: getAssetUrl('site/zeekr-007gt-7.webp'), width: 1664, height: 746 }

const HTML_ESCAPES: Record<string, string> = {
  '&': '&amp;',
  '"': '&quot;',
  '<': '&lt;',
  '>': '&gt;',
}

/** The car's figure, read out as `label`. */
export function renderCarModel(label: string) {
  const text = escapeHtml(label)
  return `<figure class="car-model" data-src="${CAR_MODEL}" data-full-src="${CAR_MODEL_FULL}" role="img" aria-label="${text}"><img class="car-model__still" src="${CAR_STILL.src}" alt="${text}" width="${CAR_STILL.width}" height="${CAR_STILL.height}" loading="lazy" decoding="async"></figure>`
}

type MdNode = { type: string; children?: MdNode[]; url?: string; value?: string }

/** A paragraph that is only the car's still becomes the car, read out as the still's alt. */
export function remarkCarModel() {
  return (tree: MdNode) => replaceStills(tree)
}

function replaceStills(node: MdNode) {
  if (!node.children) {
    return
  }

  for (const [index, child] of node.children.entries()) {
    const [only, ...rest] = child.type === 'paragraph' ? (child.children ?? []) : []
    if (only?.type === 'image' && only.url === CAR_STILL.src && rest.length === 0) {
      node.children[index] = {
        type: 'html',
        value: renderCarModel((only as { alt?: string | null }).alt ?? '') + CAR_CREDIT,
      }
      continue
    }

    replaceStills(child)
  }
}

function escapeHtml(value: string) {
  return value.replace(/[&"<>]/g, (character) => HTML_ESCAPES[character] ?? character)
}
