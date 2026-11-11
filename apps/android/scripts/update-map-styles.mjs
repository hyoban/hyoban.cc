import { mkdir, writeFile } from "node:fs/promises"
import { dirname, resolve } from "node:path"
import { fileURLToPath } from "node:url"

const scriptDirectory = dirname(fileURLToPath(import.meta.url))
const outputDirectory = resolve(
  scriptDirectory,
  "../shared/src/commonMain/composeResources/files/map",
)
const styleOrigin = "https://tiles.openfreemap.org/styles"
const styles = ["positron", "dark"]
const lesserRoad = /minor|path|pier|service|transit|dashline|taxiway|subtle/
const chineseName = [
  "let",
  "name",
  ["coalesce", ["get", "name:zh"], ["get", "name:nonlatin"], ["get", "name"]],
  [
    "let",
    "cut",
    ["index-of", ";", ["var", "name"]],
    [
      "case",
      [">", ["var", "cut"], 0],
      ["slice", ["var", "name"], 0, ["var", "cut"]],
      ["var", "name"],
    ],
  ],
]

function prepareLayer(layer) {
  const textField = layer.layout?.["text-field"]
  if (layer.type === "symbol" && (JSON.stringify(textField)?.includes("name") ?? false)) {
    layer.layout["text-field"] = chineseName
  }

  const sourceLayer = layer["source-layer"]
  const road =
    ((layer.type === "line" || layer.type === "fill") &&
      (sourceLayer === "transportation" || sourceLayer === "aeroway")) ||
    (layer.type === "symbol" &&
      (sourceLayer === "transportation" || sourceLayer === "transportation_name"))
  if (!road) return layer

  if (layer.type === "symbol" || lesserRoad.test(layer.id)) {
    layer.layout ??= {}
    layer.layout.visibility = "none"
  } else if (layer.type === "line") {
    layer.paint ??= {}
    layer.paint["line-opacity"] = 0.25
  } else if (layer.type === "fill") {
    layer.paint ??= {}
    layer.paint["fill-opacity"] = 0.25
  }
  return layer
}

async function fetchStyle(url) {
  let lastError
  for (let attempt = 1; attempt <= 4; attempt += 1) {
    try {
      const response = await fetch(url)
      if (!response.ok) throw new Error(`${response.url} answered ${response.status}`)
      return response.json()
    } catch (error) {
      lastError = error
      if (attempt < 4) await new Promise((resolveDelay) => setTimeout(resolveDelay, attempt * 500))
    }
  }
  throw lastError
}

await mkdir(outputDirectory, { recursive: true })
for (const name of styles) {
  const style = await fetchStyle(`${styleOrigin}/${name}`)
  style.layers = style.layers.map(prepareLayer)
  await writeFile(resolve(outputDirectory, `${name}.json`), `${JSON.stringify(style)}\n`)
}
