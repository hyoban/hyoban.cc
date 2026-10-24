import { z } from 'zod'

const deviceId = z.string().regex(/^[a-z0-9]+(?:-[a-z0-9]+)*$/)
const sha256 = z.string().regex(/^[a-f0-9]{64}$/)
const month = z.string().regex(/^\d{4}-(0[1-9]|1[0-2])$/)
const date = z.iso.date()
const timestamp = z.union([
  z.iso.datetime({ offset: true }),
  z.iso.datetime({ offset: true, precision: -1 }),
])
const quantity = z.int().nonnegative()
const percentage = z.number().min(0).max(100)

/** JSON Pointer into a statistical source capture; it also identifies duplicate rows. */
export const vehicleSourceSchema = z.looseObject({
  captureId: sha256,
  pointer: z.string().regex(/^\//),
  method: z.enum(['screen', 'screen_tap', 'share_report']),
})

export const vehicleCaptureSchema = z
  .looseObject({
    id: sha256,
    deviceId,
    capturedAt: timestamp,
    /** Timezone of date-only records and original timestamps without an offset. */
    timezone: z.string().min(1),
    raw: z.looseObject({
      /** Relative to data/zeekr/. Conversion never rewrites the registered source. */
      file: z.string().min(1),
      sha256,
      redacted: z.boolean(),
    }),
  })
  .refine((record) => record.id === record.raw.sha256, 'Capture id must match its raw SHA-256.')

const energy = {
  energyWh: quantity.optional(),
  drivingEnergyWh: quantity.optional(),
  parkedEnergyWh: quantity.optional(),
}

export const vehicleDaySchema = z.looseObject({
  date,
  distanceMeters: quantity.optional(),
  durationSeconds: quantity.optional(),
  ...energy,
  source: vehicleSourceSchema,
})

/** Values displayed by the app, including its rounded durations and consumption. */
export const vehicleReportSchema = z.looseObject({
  distanceMeters: quantity.optional(),
  durationSeconds: quantity.optional(),
  tripCount: quantity.optional(),
  longestTripMeters: quantity.optional(),
  longestTripDurationSeconds: quantity.optional(),
  ...energy,
  reportedConsumption: z
    .looseObject({
      whPer100Km: quantity,
      basis: z.enum(['driving', 'driving_and_parked']),
    })
    .optional(),
  chargeCount: quantity.optional(),
  distanceBeatsPercent: percentage.optional(),
})

export const vehicleChargeSchema = z.looseObject({
  startedAt: timestamp,
  type: z.enum(['AC', 'DC']),
  energyWh: quantity,
  durationSeconds: quantity,
  source: vehicleSourceSchema,
})

export const vehicleAssistedDrivingSchema = z.looseObject({
  distanceMeters: quantity.optional(),
  durationSeconds: quantity.optional(),
  nzp: z.looseObject({
    distanceMeters: quantity.optional(),
    durationSeconds: quantity.optional(),
    reportedDistancePercent: percentage.optional(),
    reportedDurationPercent: percentage.optional(),
  }),
  accLcc: z.looseObject({
    distanceMeters: quantity.optional(),
    durationSeconds: quantity.optional(),
    reportedDistancePercent: percentage.optional(),
    reportedDurationPercent: percentage.optional(),
  }),
  parkingCount: quantity.optional(),
  parkingAverageSeconds: quantity.optional(),
  daysOwned: quantity.optional(),
  beatsPercent: percentage.optional(),
})

export const vehicleMonthSchema = z
  .looseObject({
    deviceId,
    month,
    /** Latest capture contributing to this record; each source retains its own timestamp. */
    capturedAt: timestamp,
    report: vehicleReportSchema.optional(),
    days: z.array(vehicleDaySchema).optional(),
    charging: z
      .looseObject({
        reported: z.looseObject({ count: quantity, energyWh: quantity }),
        sessions: z.array(vehicleChargeSchema),
      })
      .optional(),
    assistedDriving: vehicleAssistedDrivingSchema.optional(),
    sources: z.array(vehicleSourceSchema).min(1),
  })
  .superRefine((record, context) => {
    const dates = record.days?.map((day) => day.date) ?? []
    if (
      new Set(dates).size !== dates.length ||
      dates.some((day) => day.slice(0, 7) !== record.month)
    ) {
      context.addIssue({
        code: 'custom',
        message: 'Daily dates must be unique and in the record month.',
      })
    }
    if (
      record.charging?.sessions.some((session) => session.startedAt.slice(0, 7) !== record.month)
    ) {
      context.addIssue({ code: 'custom', message: 'Charging dates must be in the record month.' })
    }
  })

/** Cumulative assistance is an observation, not an additive monthly record. */
export const vehicleAssistedDrivingCaptureSchema = z.looseObject({
  deviceId,
  capturedAt: timestamp,
  statistics: vehicleAssistedDrivingSchema,
  source: vehicleSourceSchema,
})

const yuan = z.number().nonnegative()

/** A payment for the car, in yuan, as the bookkeeping app recorded it. */
export const vehicleExpenseSchema = z.looseObject({
  at: timestamp,
  amount: yuan,
  category: z.enum([
    'purchase',
    'insurance',
    'charging',
    'parking',
    'toll',
    'car-wash',
    'maintenance',
    'repair',
    'inspection',
    'accessories',
    'fine',
  ]),
  subcategory: z
    .enum(['fast-charging', 'home-charging', 'self-service-wash', 'shop-wash', 'wash-supplies'])
    .optional(),
  note: z.string().min(1).optional(),
  /** What came back of `amount` later, a refund, subsidy or cashback, each when it did. */
  refunds: z.array(z.looseObject({ at: timestamp, amount: yuan })).optional(),
})

export const vehicleExpensesSchema = z.looseObject({
  deviceId,
  source: z.looseObject({ app: z.string().min(1), exportedAt: timestamp }),
  /** In the order the app exported them, oldest first. */
  entries: z.array(vehicleExpenseSchema),
})

export type VehicleCapture = z.output<typeof vehicleCaptureSchema>
export type VehicleMonth = z.output<typeof vehicleMonthSchema>
export type VehicleDay = z.output<typeof vehicleDaySchema>
export type VehicleCharge = z.output<typeof vehicleChargeSchema>
export type VehicleSource = z.output<typeof vehicleSourceSchema>
export type VehicleAssistedDrivingCapture = z.output<typeof vehicleAssistedDrivingCaptureSchema>
export type VehicleExpense = z.output<typeof vehicleExpenseSchema>
export type VehicleExpenses = z.output<typeof vehicleExpensesSchema>
