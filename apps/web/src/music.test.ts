import { describe, expect, test } from 'vite-plus/test'
import { formatDuration, toMusicTracks } from './music.ts'

describe('toMusicTracks', () => {
  test('keeps the playlist order and skips songs without details', () => {
    const tracks = toMusicTracks({ trackIds: [{ id: 2 }, { id: 3 }, { id: 1 }] }, [
      {
        id: 1,
        name: '晚风',
        ar: [{ name: '甲' }, { name: '乙' }],
        al: { name: '夏天', picUrl: 'http://p1.music.126.net/a.jpg' },
        dt: 185_400,
      },
      { id: 2, name: 'Morning', ar: [{ name: 'Band' }], al: {} },
    ])

    expect(tracks).toEqual([
      {
        id: 2,
        title: 'Morning',
        artists: ['Band'],
        album: undefined,
        coverUrl: undefined,
        durationSeconds: undefined,
        url: 'https://music.163.com/song?id=2',
      },
      {
        id: 1,
        title: '晚风',
        artists: ['甲', '乙'],
        album: '夏天',
        coverUrl: 'https://p1.music.126.net/a.jpg',
        durationSeconds: 185,
        url: 'https://music.163.com/song?id=1',
      },
    ])
  })
})

test('formatDuration', () => {
  expect(formatDuration(185)).toBe('3:05')
  expect(formatDuration(59)).toBe('0:59')
})
