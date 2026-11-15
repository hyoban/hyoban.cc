import { fileURLToPath } from 'node:url'
import { expect, test } from 'vite-plus/test'
import { readBills } from './store.ts'

const fixtures = fileURLToPath(
  new URL('../../../../packages/data-spec/test/fixtures/data', import.meta.url),
)

test('the site reads the public bills alone, down to their public fields', () => {
  expect(readBills(fixtures)).toEqual([
    {
      v: 1,
      visibility: 'public',
      kind: 'expense',
      amount: 86,
      currency: 'CNY',
      category: '餐饮 › 晚餐',
      note: '面馆',
      uid: '01KNC1KQ00WZ3M8E5R7T2Y4B6N',
      at: '2026-04-04T18:50:00+08:00',
      place: 'hangzhou',
    },
    {
      v: 1,
      visibility: 'public',
      kind: 'expense',
      amount: 20,
      currency: 'CNY',
      category: '交通 › 停车',
      uid: '01KNE2A9C0R4T6V8X0Z2B4D6F8',
      at: '2026-04-05T09:30:00+08:00',
    },
  ])
})
