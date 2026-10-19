import assetConfig from './data/asset-config.json' with { type: 'json' }

export function getAssetUrl(key: string) {
  const encodedKey = key
    .split('/')
    .map((segment) => encodeURIComponent(segment))
    .join('/')

  return `${assetConfig.origin}/${encodedKey}`
}
