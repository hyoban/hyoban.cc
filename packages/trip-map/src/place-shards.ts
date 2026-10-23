// Places' drawer content served a few files at a time rather than one file per place:
// each place falls in one of PLACE_SHARDS files by its id, and a file holds a
// `<template data-trip-map-place="<id>">` per place in it. The site renders the files
// with getPlaceShard, and the map's default `getPlaceUrl` asks for the place's file.

export const PLACE_SHARDS = 8

/** Which of `shards` files a place's drawer content is in: FNV-1a of its id. */
export function getPlaceShard(id: string, shards = PLACE_SHARDS) {
  let hash = 0x811c9dc5

  for (let index = 0; index < id.length; index++) {
    hash ^= id.charCodeAt(index)
    hash = Math.imul(hash, 0x01000193)
  }

  return (hash >>> 0) % shards
}
