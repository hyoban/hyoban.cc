import { parse as parseYaml, stringify as stringifyYaml } from 'yaml'
import { z } from 'zod'
import { isLocationId } from '../data/locations.ts'

const semanticImageFileSchema = z.string().trim().regex(
  /^[a-z0-9]+(?:-[a-z0-9]+)*\.(?:avif|gif|jpeg|jpg|png|webp)$/,
  'Use a safe kebab-case semantic image filename.',
)

const semanticVideoFileSchema = z.string().trim().regex(
  /^[a-z0-9]+(?:-[a-z0-9]+)*\.(?:mov|mp4|webm)$/,
  'Use a safe kebab-case semantic video filename.',
)

const momentImageSchema = z.strictObject({
  alt: z.string(),
  file: semanticImageFileSchema,
  type: z.literal('image'),
})

const momentVideoSchema = z.strictObject({
  alt: z.string(),
  file: semanticVideoFileSchema,
  poster: semanticImageFileSchema.optional(),
  type: z.literal('video'),
})

const momentMediaSchema = z.discriminatedUnion('type', [
  momentImageSchema,
  momentVideoSchema,
])

export const momentFrontmatterSchema = z.strictObject({
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
