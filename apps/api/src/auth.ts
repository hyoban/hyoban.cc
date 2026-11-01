// One owner, so a bearer token is all there is, and every request needs one: any of the
// comma-separated tokens in the API_TOKENS secret, one for the recording app and one for
// the site's build, so each can be revoked alone.

/** Whether the request carries one of the known tokens. */
export async function authorize(request: Request, tokens = ''): Promise<boolean> {
  const given = request.headers.get('Authorization')?.match(/^Bearer (\S+)$/)?.[1]

  if (!given) {
    return false
  }

  // Compare digests, so how long a comparison takes says nothing about the token.
  const givenDigest = await digest(given)

  for (const token of tokens.split(',')) {
    if (token.trim() && equal(givenDigest, await digest(token.trim()))) {
      return true
    }
  }

  return false
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
