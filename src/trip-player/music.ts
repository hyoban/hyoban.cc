// Background music: a looping track that fades in, played on its own for card
// playback, or also mixed into the video's audio track while recording.

const MUSIC_FADE_IN = 1
const MUSIC_VOLUME = 0.8
const MUSIC_FADE_OUT = 2
const MUSIC_MUTE = 0.15

export type Music = {
  context: AudioContext
  /** Present while recording: the stream the video's audio track is taken from. */
  destination?: MediaStreamAudioDestinationNode
  gain: GainNode
  output: GainNode
  source: AudioBufferSourceNode
}

/** Decodes a track into a silent looping source; `fadeMusicIn` starts it. */
export async function loadMusic(
  src: string,
  options: { audible: boolean; record: boolean },
): Promise<Music | undefined> {
  if (!src) {
    return undefined
  }

  try {
    const context = new AudioContext()
    const buffer = await fetch(src)
      .then((response) => response.arrayBuffer())
      .then((data) => context.decodeAudioData(data))
    const source = context.createBufferSource()
    const gain = context.createGain()
    const output = context.createGain()
    const destination = options.record ? context.createMediaStreamDestination() : undefined

    source.buffer = buffer
    source.loop = true
    gain.gain.value = 0
    output.gain.value = options.audible ? 1 : 0
    source.connect(gain)
    gain.connect(output)
    output.connect(context.destination)

    if (destination) {
      gain.connect(destination)
    }

    await context.resume()
    return { context, gain, output, source, ...(destination ? { destination } : {}) }
  } catch {
    // Playback without music is better than none.
    return undefined
  }
}

export function fadeMusicIn(music: Music) {
  const now = music.context.currentTime

  music.gain.gain.setValueAtTime(0, now)
  music.gain.gain.linearRampToValueAtTime(MUSIC_VOLUME, now + MUSIC_FADE_IN)
  music.source.start()
}

/** Fades out so the music ends with the video, `endsIn` seconds from now. */
export function fadeMusicOut(music: Music, endsIn: number) {
  const fadeStart = music.context.currentTime + Math.max(endsIn - MUSIC_FADE_OUT, 0)

  music.gain.gain.setValueAtTime(MUSIC_VOLUME, fadeStart)
  music.gain.gain.linearRampToValueAtTime(0, fadeStart + MUSIC_FADE_OUT)
}

export function setMusicAudible(music: Music, audible: boolean) {
  const now = music.context.currentTime

  music.output.gain.cancelScheduledValues(now)
  music.output.gain.setValueAtTime(music.output.gain.value, now)
  music.output.gain.linearRampToValueAtTime(audible ? 1 : 0, now + MUSIC_MUTE)
}

export function closeMusic(music: Music) {
  music.source.stop()
  void music.context.close()
}
