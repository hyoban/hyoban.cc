import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import { isSoftwareRenderer, judgeDevice } from '../src/map/device.ts'

test('tells a renderer drawing on the CPU from a GPU', () => {
  assert.equal(
    isSoftwareRenderer(
      'ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero)), SwiftShader driver)',
    ),
    true,
  )
  assert.equal(isSoftwareRenderer('llvmpipe (LLVM 15.0.7, 256 bits)'), true)
  assert.equal(
    isSoftwareRenderer('ANGLE (Microsoft, Microsoft Basic Render Driver Direct3D11)'),
    true,
  )
  assert.equal(
    isSoftwareRenderer('ANGLE (Apple, ANGLE Metal Renderer: Apple M4 Pro, Unspecified Version)'),
    false,
  )
  assert.equal(isSoftwareRenderer('Adreno (TM) 618'), false)
})

test('judges a device slow by its renderer, its memory or how long the work takes', () => {
  const gpu = 'ANGLE (Apple, ANGLE Metal Renderer: Apple M4 Pro, Unspecified Version)'
  let measured = 0
  const fast = () => {
    measured++
    return 2.5
  }

  assert.equal(judgeDevice({ memoryGb: 8, renderer: gpu, workMs: fast }), false)
  assert.equal(measured, 1)
  // Known slow without measuring.
  assert.equal(judgeDevice({ memoryGb: 8, renderer: 'SwiftShader', workMs: fast }), true)
  assert.equal(judgeDevice({ memoryGb: 2, renderer: gpu, workMs: fast }), true)
  assert.equal(measured, 1)
  // Five times slower than a fast laptop, or more.
  assert.equal(judgeDevice({ memoryGb: undefined, renderer: undefined, workMs: () => 15 }), true)
  assert.equal(judgeDevice({ memoryGb: undefined, renderer: gpu, workMs: () => 9 }), false)
})
