// One owner, so a bearer token is all there is: any of the comma-separated tokens in
// the API_TOKENS secret, one for the recording app and one for the site's build, so
// each can be revoked alone.

export type Access = 'public' | 'owner' | 'denied'

/** Public without a token, the owner with a known one, denied with any other. */
export async function authorize(request: Request, tokens = ''): Promise<Access> {
  const header = request.headers.get('Authorization')

  if (header === null) {
    return 'public'
  }

  const given = header.match(/^Bearer (\S+)$/)?.[1]

  if (!given) {
    return 'denied'
  }

  // Compare digests, so how long a comparison takes says nothing about the token.
  const givenDigest = await digest(given)

  for (const token of tokens.split(',')) {
    if (token.trim() && equal(givenDigest, await digest(token.trim()))) {
      return 'owner'
    }
  }

  return 'denied'
}

async function digest(value: string) {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value)))
}

function equal(first: Uint8Array, second: Uint8Array) {
  let difference = first.length ^ second.length

  for (let index = 0; index < first.length; index += 1) {
    difference |= first[index]! ^ (second[index] ?? 0)
  }

  return difference === 0
}
