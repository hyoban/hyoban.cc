import siteConfig from '../../../site.config.json' with { type: 'json' }

// The site the API serves, from the repository's site.config.json: where its pages and
// images live, and the name the API goes by.

export const SITE_URL = siteConfig.site.url
export const IMAGE_ORIGIN = siteConfig.assets.origin
/** `hyoban.cc`: what the API answers to and tells the servers it calls. */
export const SITE_HOST = new URL(SITE_URL).host
