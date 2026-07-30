import { parse as parseYaml, stringify as stringifyYaml } from 'yaml'
import { z } from 'zod'
import { isLocationId } from '../data/locations.ts'

const momentMediaSchema = z.object({
  alt: z.string(),
  file: z.string().trim().min(1),
  poster: z.string().trim().min(1).optional(),
  type: z.enum(['image', 'video']),
})

export const momentFrontmatterSchema = z.object({
  hidden: z.boolean().default(false),
  location: z.string().refine(isLocationId, {
    message: 'Unknown calendar map location id.',
  }).optional(),
  media: z.array(momentMediaSchema).default([]),
  occurredAt: z.union([
    z.iso.date(),
    z.iso.datetime({ offset: true }),
  ]),
})

export type MomentFrontmatter = z.infer<typeof momentFrontmatterSchema>

export type CanonicalMoment = MomentFrontmatter & {
  id: string
  text: string
}

export type MomentDocumentInput = MomentFrontmatter & {
  text: string
}

export function parseMomentDocument(document: string, options: { id: string }): CanonicalMoment {
  const match = document.match(/^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n([\s\S]*))?$/)

  if (!match) {
    throw new Error(`Invalid Moment document: ${options.id}`)
  }

  const frontmatter = momentFrontmatterSchema.parse(parseYaml(match[1]!))

  return {
    ...frontmatter,
    id: options.id,
    text: (match[2] ?? '').trim(),
  }
}

export function serializeMomentDocument(moment: MomentDocumentInput) {
  const frontmatter = {
    occurredAt: moment.occurredAt,
    ...(moment.hidden ? { hidden: true } : {}),
    ...(moment.location ? { location: moment.location } : {}),
    media: moment.media,
  }

  momentFrontmatterSchema.parse(frontmatter)

  const lines = [
    '---',
    stringifyMomentFrontmatter(frontmatter),
    '---',
  ]

  if (moment.text) {
    lines.push('', moment.text.trim())
  }

  lines.push('')
  return lines.join('\n')
}

function stringifyMomentFrontmatter(frontmatter: object) {
  return stringifyYaml(frontmatter, { lineWidth: 0 })
    .trimEnd()
    .replace(
      /^occurredAt: (?!")(.+)$/m,
      'occurredAt: "$1"',
    )
}
