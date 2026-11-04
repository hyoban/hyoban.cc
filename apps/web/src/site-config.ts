import siteConfig from '../../../site.config.json' with { type: 'json' }

// Who the site belongs to and where it lives, from the repository's site.config.json: the
// one file a fork changes to make the site its own (FORKING.md).

export const site = siteConfig.site
export const assets = siteConfig.assets
export const api = siteConfig.api
/** The NetEase playlist the media page lists, or null for none. */
export const MUSIC_PLAYLIST_ID: number | null = siteConfig.music.neteasePlaylistId
/** The site's host, `hyoban.cc`, as share cards and pictures print it. */
export const SITE_HOST = new URL(site.url).host
