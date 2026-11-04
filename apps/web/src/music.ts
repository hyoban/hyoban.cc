import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'

// The songs on /media/'s 音乐 tab: the owner's liked-songs playlist on NetEase Cloud Music,
// read at build time from its public web API, which needs no login. The playlist lists
// every track's id but details only a few, so the songs are looked up by id in a second
// request. The last playlist read is kept under node_modules/.astro, which Cloudflare's
// build cache keeps, and a build that can't reach NetEase shows that instead; with
// neither, the build fails.

export const MUSIC_PLAYLIST_ID = 2948809894

const API_BASE_URL = 'https://music.163.com/api'
const CACHE_FILE = join(process.cwd(), 'node_modules', '.astro', 'netease-playlist.json')
const REQUEST_HEADERS = {
  Referer: 'https://music.163.com/',
  'User-Agent': 'Mozilla/5.0 (hyoban.cc build)',
}

export type MusicTrack = {
  album?: string | undefined
  artists: string[]
  coverUrl?: string | undefined
  durationSeconds?: number | undefined
  id: number
  title: string
  url: string
}

type NeteasePlaylist = {
  trackIds?: { id: number }[]
}

type NeteaseSong = {
  al?: { name?: string; picUrl?: string }
  ar?: { name?: string }[]
  dt?: number
  id: number
  name?: string
}

/** The playlist's songs in its own order, newest liked first, from its ids and their details. */
export function toMusicTracks(playlist: NeteasePlaylist, songs: NeteaseSong[]): MusicTrack[] {
  const songsById = new Map(songs.map((song) => [song.id, song]))

  return (playlist.trackIds ?? []).flatMap(({ id }) => {
    const song = songsById.get(id)

    if (!song?.name) {
      return []
    }

    return [
      {
        album: song.al?.name || undefined,
        artists: (song.ar ?? [])
          .map((artist) => artist.name)
          .filter((name): name is string => Boolean(name)),
        coverUrl: song.al?.picUrl
          ? `${song.al.picUrl.replace(/^http:/, 'https:')}?param=120y120`
          : undefined,
        durationSeconds: song.dt ? Math.round(song.dt / 1000) : undefined,
        id,
        title: song.name,
        url: `https://music.163.com/song?id=${id}`,
      },
    ]
  })
}

/** `3:05`: a song's length. */
export function formatDuration(seconds: number) {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

let tracksPromise: Promise<MusicTrack[]> | undefined

/** The playlist's songs, read once per build. */
export function getMusicTracks() {
  tracksPromise ??= readMusicTracks()
  return tracksPromise
}

async function readMusicTracks() {
  try {
    const tracks = await fetchMusicTracks(MUSIC_PLAYLIST_ID)
    await mkdir(dirname(CACHE_FILE), { recursive: true })
    await writeFile(CACHE_FILE, `${JSON.stringify(tracks)}\n`)
    return tracks
  } catch (error) {
    const cached = await readFile(CACHE_FILE, 'utf8').catch(() => undefined)

    if (!cached) {
      throw new Error(
        `Couldn't read NetEase playlist ${MUSIC_PLAYLIST_ID}, and no earlier copy is cached.`,
        { cause: error },
      )
    }

    console.warn(
      `Couldn't read NetEase playlist ${MUSIC_PLAYLIST_ID}, showing the cached copy:`,
      error,
    )
    return JSON.parse(cached) as MusicTrack[]
  }
}

async function fetchMusicTracks(playlistId: number) {
  const { playlist } = await fetchNetease<{ playlist?: NeteasePlaylist }>(
    `/v6/playlist/detail?id=${playlistId}&n=0`,
  )

  if (!playlist?.trackIds?.length) {
    throw new Error('The playlist came back without tracks.')
  }

  const songs: NeteaseSong[] = []

  // The song lookup takes a few hundred ids at a time.
  for (let index = 0; index < playlist.trackIds.length; index += 500) {
    const ids = playlist.trackIds.slice(index, index + 500).map(({ id }) => ({ id }))
    const result = await fetchNetease<{ songs?: NeteaseSong[] }>(
      '/v3/song/detail',
      new URLSearchParams({ c: JSON.stringify(ids) }),
    )
    songs.push(...(result.songs ?? []))
  }

  return toMusicTracks(playlist, songs)
}

async function fetchNetease<T>(path: string, body?: URLSearchParams): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    body: body ?? null,
    headers: REQUEST_HEADERS,
    method: body ? 'POST' : 'GET',
    signal: AbortSignal.timeout(15_000),
  })

  if (!response.ok) {
    throw new Error(`NetEase answered ${response.status} for ${path}`)
  }

  const result = (await response.json()) as T & { code?: number }

  if (result.code !== 200) {
    throw new Error(`NetEase answered code ${result.code} for ${path}`)
  }

  return result
}
