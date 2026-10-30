// A Bilibili video link on a line of its own in a Moment plays inline: on the Moment
// itself, and on its trip card, where it starts by itself, muted under the trip's music.

const VIDEO_URL =
  /^https?:\/\/(?:www\.|m\.)?bilibili\.com\/video\/(?:av(\d+)|(BV[0-9A-Za-z]{10}))\/?(?:\?(\S*))?$/

/** The embeddable player for a line that is just a Bilibili video link. */
export function getBilibiliPlayer(line: string, options: { autoplay?: boolean } = {}) {
  const match = line.trim().match(VIDEO_URL)

  if (!match) {
    return
  }

  const [, aid, bvid, query] = match
  const params = new URLSearchParams({
    ...(aid ? { aid } : { bvid: bvid! }),
    p: new URLSearchParams(query).get('p') ?? '1',
    autoplay: options.autoplay ? '1' : '0',
    danmaku: '0',
    high_quality: '1',
    ...(options.autoplay ? { muted: '1' } : {}),
  })

  return `https://player.bilibili.com/player.html?${params}`
}

/** The first Bilibili video in a Moment's text, and the text without its link line. */
export function takeBilibiliVideo(text: string, options: { autoplay?: boolean } = {}) {
  const lines = text.split('\n')
  const index = lines.findIndex((line) => getBilibiliPlayer(line))

  if (index === -1) {
    return { text }
  }

  return {
    text: lines
      .filter((_, lineIndex) => lineIndex !== index)
      .join('\n')
      .trim(),
    video: getBilibiliPlayer(lines[index]!, options)!,
  }
}
