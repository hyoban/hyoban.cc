import type { ResolvedMomentMedia } from './index.ts'

/** The media a cover, a collage or a share card may pick: all but the utility ones. */
export function getShowcaseMedia<M extends Pick<ResolvedMomentMedia, 'utility'>>(
  media: readonly M[],
) {
  return media.filter((item) => !item.utility)
}
