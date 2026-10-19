// Responses, and the error a handler throws to send one.

export class HttpError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly headers: Record<string, string> = {},
  ) {
    super(message)
  }
}

export function json(body: unknown, status = 200, headers: Record<string, string> = {}) {
  return Response.json(body, { status, headers })
}

export function error(status: number, message: string, headers: Record<string, string> = {}) {
  return json({ error: message }, status, headers)
}

/** The request's JSON body, or a 400. */
export async function readBody(request: Request): Promise<unknown> {
  try {
    return await request.json()
  } catch {
    throw new HttpError(400, 'The body is not JSON.')
  }
}

/** A schema's output for `value`, or a 422 that says what is wrong. */
export function validate<T>(
  schema: {
    safeParse(value: unknown): { success: true; data: T } | { success: false; error: Error }
  },
  value: unknown,
): T {
  const result = schema.safeParse(value)

  if (!result.success) {
    throw new HttpError(422, result.error.message)
  }

  return result.data
}
