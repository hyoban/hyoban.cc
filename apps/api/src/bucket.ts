// The part of R2 the API uses, so the tests can hand it a map instead.

export interface Bucket {
  put(
    key: string,
    value: ReadableStream | ArrayBuffer | string,
    options?: { httpMetadata?: { contentType?: string } },
  ): Promise<StoredObject | null>
  get(key: string): Promise<(StoredObject & { body: ReadableStream }) | null>
  head(key: string): Promise<StoredObject | null>
  delete(keys: string | string[]): Promise<void>
}

export interface StoredObject {
  key: string
  size: number
  /** The MD5 of the contents, for an object put in one piece. */
  etag: string
  httpMetadata?: { contentType?: string }
}

/** Where an original waits, between its upload and its Moment's publishing. */
export function getUploadKey(momentUid: string, mediaUid: string, part: UploadPart) {
  return `uploads/${momentUid}/${mediaUid}/${part}`
}

/** A live photo has both; an image only the first and a video only the second. */
export type UploadPart = 'image' | 'video'
