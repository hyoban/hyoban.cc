import { fileURLToPath } from 'node:url'
import { expect, test } from 'vite-plus/test'
import { readCheckins } from './store.ts'

const fixtures = fileURLToPath(
  new URL('../../../../packages/data-spec/test/fixtures/data', import.meta.url),
)

test('the map reads the public check-ins alone, without their source', () => {
  const checkins = readCheckins(fixtures)

  expect(checkins).toEqual([
    {
      v: 1,
      venue: 'Noodle House',
      alias: '小面馆',
      uid: '01JT7M4AA0RNNTBNAYBC0VYKVX',
      at: '2025-05-02T12:30:00+08:00',
      place: 'xiao-mian-guan',
    },
  ])
})
