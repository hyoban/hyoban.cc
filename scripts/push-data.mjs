import { relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { pushData } from './lib/data-sync.mjs'

// pnpm data:push [--local] [--dry-run] [--place <id>]... <record>...
//
// Writes records from data/ into the API's database without removing anything: each
// Moment or trip file given (data/moments/…/<uid>.json, data/trips/<id>.json), the
// places they name, and any --place. For what was written outside the API, like a Moment
// from the create-moment skill. The deployed database by default; --local writes the
// one `wrangler dev` uses, and --dry-run only writes the SQL under .artifacts/import.

const root = fileURLToPath(new URL('../', import.meta.url))
const options = parseArguments(process.argv.slice(2))
const pushed = await pushData(root, options, {
  generator: 'hyoban.cc data:push',
  remote: !options.local,
  dryRun: options.dryRun,
})

console.log(
  `${options.dryRun ? 'Would push' : 'Pushed'} ${pushed.moments} Moment(s), ${pushed.trips} trip(s) and ${pushed.places} place(s) ${options.local ? 'locally' : 'to the API'}.`,
)

if (pushed.routes) {
  console.log(
    `Routes: ${pushed.routes.fetched} fetched, ${pushed.routes.removed} removed, ${pushed.routes.failed} failed, ${pushed.routes.left} left for next time.`,
  )
}

if (pushed.built !== undefined) {
  console.log(pushed.built ? 'The site is building again.' : "The site isn't building: see above.")
}

function parseArguments(argv) {
  const options = { moments: [], trips: [], places: [], local: false, dryRun: false }

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]

    if (argument === '--local') {
      options.local = true
    } else if (argument === '--dry-run') {
      options.dryRun = true
    } else if (argument === '--place' && argv[index + 1]) {
      options.places.push(argv[(index += 1)])
    } else {
      // A record's file, relative to data/ like the store names them.
      const file = relative(resolve(root, 'data'), resolve(argument)).split('\\').join('/')

      if (/^moments\/\d{4}\/\d{2}\/[0-9A-HJKMNP-TV-Z]{26}\.json$/.test(file)) {
        options.moments.push(file)
      } else if (/^trips\/[a-z0-9-]+\.json$/.test(file)) {
        options.trips.push(file)
      } else {
        throw new Error(`Not a Moment or trip under data/: ${argument}`)
      }
    }
  }

  if (options.moments.length + options.trips.length + options.places.length === 0) {
    throw new Error(
      'Usage: pnpm data:push [--local] [--dry-run] [--place <id>]... <data/moments/…/<uid>.json | data/trips/<id>.json>...',
    )
  }

  return options
}
