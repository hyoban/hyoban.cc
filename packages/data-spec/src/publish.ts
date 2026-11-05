// The rules for publishing: what type of file a media item is stored as; its key is
// `getMediaKey`'s, by uids alone. The API's.

/** The file extension for a media type the API takes, or undefined for any other. */
export function getExtension(contentType: string) {
  return EXTENSIONS[contentType.split(';')[0]!.trim().toLowerCase()]
}

const EXTENSIONS: Record<string, string> = {
  'image/avif': 'avif',
  'image/gif': 'gif',
  'image/heic': 'heic',
  'image/heif': 'heif',
  'image/jpeg': 'jpg',
  'image/png': 'png',
  'image/webp': 'webp',
  'video/mp4': 'mp4',
  'video/quicktime': 'mov',
  'video/webm': 'webm',
}
