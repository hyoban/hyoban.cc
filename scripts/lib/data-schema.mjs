import { z } from 'astro/zod'

import {
  SCHEMA_VERSION,
  devicesSchema,
  manifestSchema,
  momentRecordSchema,
  placesSchema,
  routesSchema,
  tripRecordSchema,
  vehicleCaptureSchema,
  vehicleMonthSchema,
  vehicleAssistedDrivingCaptureSchema,
  vehicleExpensesSchema,
} from '@hyoban/data-spec'

/** The spec's schemas by the name of the JSON Schema file each becomes. */
export function getDataSchemas() {
  return {
    devices: devicesSchema,
    manifest: manifestSchema,
    moment: momentRecordSchema,
    places: placesSchema,
    routes: routesSchema,
    trip: tripRecordSchema,
    'vehicle-capture': vehicleCaptureSchema,
    'vehicle-month': vehicleMonthSchema,
    'vehicle-assisted-driving': vehicleAssistedDrivingCaptureSchema,
    'vehicle-expenses': vehicleExpensesSchema,
  }
}

export function getSchemaFile(name) {
  return `${name}.schema.json`
}

/** One schema as JSON Schema text, as `pnpm data:schema` writes it. */
export function renderSchema(name, schema) {
  return `${JSON.stringify(
    {
      $id: `https://hyoban.cc/data/schema/v${SCHEMA_VERSION}/${getSchemaFile(name)}`,
      ...z.toJSONSchema(schema, { io: 'input', target: 'draft-2020-12', unrepresentable: 'any' }),
    },
    null,
    2,
  )}\n`
}
