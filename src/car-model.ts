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

/** What a year review's car stands on, and the year the page plays through day by day as it
 * scrolls (src/components/YearDriving.astro). */
export interface CarYear {
  /** The legs driven, as path data in a box `width` by `height` (src/year-map.ts). */
  floor?: { d: string; width: number; height: number } | undefined
  /** What the totals are of, over them once the year's driven through: `2026 年`. */
  caption: string
  /** Its first day, `YYYY-MM-DD`; the days follow it one by one. */
  start: string
  /** Each day's metres driven, Wh charged and fen spent. */
  days: [meters: number, wh: number, fen: number][]
  /** Each day's level of distance on the driving graph, `0` to `4`, or `.` unrecorded. */
  levels: string
}

/** A day's record for the year the car drives through. */
export interface CarDayInput {
  date: string
  meters?: number | undefined
  wh?: number
  fen?: number
}

/**
 * The days from the earliest to the latest in `inputs`, every one in between kept, with what
 * each drove, charged and spent summed; `level` gives a recorded day's level of distance.
 */
export function getCarDays(
  inputs: readonly CarDayInput[],
  level: (meters: number) => number,
): Pick<CarYear, 'start' | 'days' | 'levels'> | undefined {
  if (inputs.length === 0) return undefined
  const byDate = new Map<string, { meters?: number; wh: number; fen: number }>()
  for (const { date, meters, wh = 0, fen = 0 } of inputs) {
    const day = byDate.get(date) ?? { wh: 0, fen: 0 }
    if (meters !== undefined) day.meters = (day.meters ?? 0) + meters
    day.wh += wh
    day.fen += fen
    byDate.set(date, day)
  }
  const dates = [...byDate.keys()].sort()
  const start = dates[0]!
  const end = dates.at(-1)!
  const days: CarYear['days'] = []
  let levels = ''
  for (let index = 0; ; index++) {
    const date = getCarDate(start, index)
    const day = byDate.get(date)
    days.push([day?.meters ?? 0, day?.wh ?? 0, day?.fen ?? 0])
    levels += day?.meters === undefined ? '.' : String(level(day.meters))
    if (date === end) break
  }
  return { start, days, levels }
}

/** What had been driven, charged and spent by the end of each of a year's days, and on how
 * many days it had been driven. */
export function sumCarDays(days: CarYear['days']) {
  const sums: CarSum[] = []
  let sum = { meters: 0, wh: 0, fen: 0, driven: 0 }
  for (const [meters, wh, fen] of days) {
    sum = {
      meters: sum.meters + meters,
      wh: sum.wh + wh,
      fen: sum.fen + fen,
      driven: sum.driven + (meters > 0 ? 1 : 0),
    }
    sums.push(sum)
  }
  return sums
}

type CarSum = { meters: number; wh: number; fen: number; driven: number }

const numbers = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 })

/** A sum's figures as both the page and the speeding car show them. */
export function formatCarSum({ meters, wh, fen }: CarSum) {
  return {
    km: numbers.format(meters / 1000),
    charged: `充入 ${numbers.format(wh / 1000)} kWh`,
    spent: `花费 ¥${numbers.format(fen / 100)}`,
  }
}

/** The `YYYY-MM-DD` `index` days after `start`. */
export function getCarDate(start: string, index: number) {
  return new Date(Date.parse(`${start}T00:00Z`) + index * 86_400_000).toISOString().slice(0, 10)
}

/** `2026 年 5 月 29 日` for `2026-05-29`, or `5 月 29 日` without `withYear`. */
export function formatCarDate(date: string, withYear = true) {
  const day = `${Number(date.slice(5, 7))} 月 ${Number(date.slice(8))} 日`
  return withYear ? `${date.slice(0, 4)} 年 ${day}` : day
}

/** The car's figure, read out as `label`; in a year review, standing on its `year`. */
export function renderCarModel(label: string, year?: CarYear) {
  const text = escapeHtml(label)
  const data = year ? ` data-year="${escapeHtml(JSON.stringify(year))}"` : ''
  return `<figure class="car-model" data-src="${CAR_MODEL}" data-full-src="${CAR_MODEL_FULL}"${data} role="img" aria-label="${text}"><img class="car-model__still" src="${CAR_STILL.src}" alt="${text}" width="${CAR_STILL.width}" height="${CAR_STILL.height}" loading="lazy" decoding="async"></figure>`
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
