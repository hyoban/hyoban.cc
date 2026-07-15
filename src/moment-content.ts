import { parse as parseYaml, stringify as stringifyYaml } from 'yaml'
import { z } from 'zod'
import { isLocationId } from './data/locations.ts'

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
  occurredOn: z.iso.date().optional(),
  publishedAt: z.coerce.date(),
  sourceUrl: z.url().optional(),
}).superRefine((moment, context) => {
  if (moment.sourceUrl) {
    return
  }

  for (const [index, media] of moment.media.entries()) {
    if (!media.alt.trim()) {
      context.addIssue({
        code: 'custom',
        message: 'Canonical Moment media requires alt text.',
        path: ['media', index, 'alt'],
      })
    }
  }
})

export type MomentFrontmatter = z.infer<typeof momentFrontmatterSchema>

export type CanonicalMoment = Omit<MomentFrontmatter, 'sourceUrl'> & {
  id: string
  provenance?: {
    url: string
  }
  text: string
}

export type MomentDocumentInput = Omit<MomentFrontmatter, 'publishedAt' | 'sourceUrl'> & {
  provenance?: {
    url: string
  }
  publishedAt: string
  text: string
}

export function parseMomentDocument(document: string, options: { id: string }): CanonicalMoment {
  const match = document.match(/^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n([\s\S]*))?$/)

  if (!match) {
    throw new Error(`Invalid Moment document: ${options.id}`)
  }

  const frontmatter = momentFrontmatterSchema.parse(parseYaml(match[1]!))
  const { sourceUrl, ...data } = frontmatter

  return {
    ...data,
    id: options.id,
    ...(sourceUrl ? { provenance: { url: sourceUrl } } : {}),
    text: (match[2] ?? '').trim(),
  }
}

export function serializeMomentDocument(moment: MomentDocumentInput) {
  const frontmatter = {
    publishedAt: moment.publishedAt,
    ...(moment.occurredOn ? { occurredOn: moment.occurredOn } : {}),
    ...(moment.hidden ? { hidden: true } : {}),
    ...(moment.location ? { location: moment.location } : {}),
    ...(moment.provenance ? { sourceUrl: moment.provenance.url } : {}),
    media: moment.media,
  }

  momentFrontmatterSchema.parse(frontmatter)

  const lines = [
    '---',
    stringifyYaml(frontmatter, { lineWidth: 0 }).trimEnd(),
    '---',
  ]

  if (moment.text) {
    lines.push('', moment.text.trim())
  }

  lines.push('')
  return lines.join('\n')
}
