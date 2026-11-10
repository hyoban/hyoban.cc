package cc.hyoban.moments.ui

import kotlinx.serialization.Serializable

// The app's pages, each as the route the navigation goes to it by, the same on every device.

@Serializable
object Feed

@Serializable
object Atlas


@Serializable
data class Moment(val uid: String)

@Serializable
data class Viewer(val uid: String, val index: Int)

@Serializable
data class Trip(val id: String)

/** A trip's name, days and who sees it, to change, or with no [id] to make one, from [start] to [end] and named [title] when they're given. */
@Serializable
data class TripEdit(val id: String? = null, val start: String? = null, val end: String? = null, val title: String? = null)

/** A trip played back along its route. */
@Serializable
data class TripPlay(val id: String)

/**
 * One day's Moments, by its `YYYY-MM-DD`, and the key of the card it grows out of: a calendar
 * day, a day's pill or a Moment's row for it. Opened from elsewhere, a menu, it slides in.
 */
@Serializable
data class Day(val date: String)

@Serializable
data class PlaceMoments(val id: String)

/** The 我 tab: the changes waiting to be published, the ledger's month and the settings, each a page under it. */
@Serializable
object Preferences

/** 同步与来源: the API, its token, the photos' origin and the map's styles. */
@Serializable
object SyncSettings

/** How footprints are recorded, a page under 我. */
@Serializable
object FootprintSettings

/** The physical side key and the action assigned to each gesture. */
@Serializable
object SideKeySettings

/** 相机: the paired a7C II, its location and what it needs. */
@Serializable
object CameraSettings

/** Who sees what by default: the Moments about tech, the books' new bills and the public spans of the footprints. */
@Serializable
object Items

/** 家: where the map's 可能的旅程 count as home. */
@Serializable
object Homes

/** 导入: Swarm, iCost and GPX files brought in, each where it belongs. */
@Serializable
object Imports

/**
 * 东西: the devices, the subscriptions and the car on one page, [kind] the one shown (devices,
 * subscriptions or car; none: the one looked at last), with the sheet of the thing [open] over it.
 */
@Serializable
data class Things(val kind: String? = null, val open: String? = null)

/** 纪念日: the days that matter, with the sheet of the one [open] over the page. */
@Serializable
data class Occasions(val open: String? = null)

/** A Moment to write, kept on the phone, with a bill or the [bill] alone. */
@Serializable
data class NewMoment(val bill: Boolean = false)

/** One of the phone's Moments, to change. */
@Serializable
data class EditMoment(val uid: String)

/**
 * A Moment to write, or one of [uid] to change, opened from the medium breakpoint on: a basic
 * dialog over the page it was opened from, as Material 3 keeps full-screen dialogs for compact
 * windows, where [NewMoment] and [EditMoment] are pages of their own.
 */
@Serializable
data class EditorDialog(val uid: String? = null, val bill: Boolean = false)

/** The changes kept on the phone, to look over and publish. */
@Serializable
object Changes

/** Searching the Moments. */
@Serializable
object Search

/** The ledger, opened from 我 on its [tab]: 0 the bills, 1 the sums, 2 the accounts. */
@Serializable
data class LedgerHome(val tab: Int = 0)

/** A bill to write, or one of [edit] to change; what a new one starts with, as [LedgerEntryArgs] has it. */
@Serializable
data class LedgerEntry(
    val edit: String? = null,
    val kind: String? = null,
    val related: String? = null,
    val moment: String? = null,
    val date: String? = null,
    val account: String? = null,
    val trip: String? = null,
    /** A payment's, read by 自动记账: its cents, epoch milliseconds, note and category. */
    val amount: String? = null,
    val time: String? = null,
    val note: String? = null,
    val category: String? = null,
)

/** One bill of the ledger. */
@Serializable
data class LedgerBill(val id: String)

/** One account of the ledger and its bills. */
@Serializable
data class LedgerAccount(val id: String)

/** An account to add, or one of [id] to change. */
@Serializable
data class LedgerAccountEdit(val id: String? = null)

/** The ledger's settings; with [import], its iCost import's file picker opened at once. */
@Serializable
data class LedgerSettings(val import: Boolean = false)

/** Bills picked as [LedgerFilter] has it. */
@Serializable
data class LedgerList(
    val title: String? = null,
    val book: String? = null,
    val account: String? = null,
    val category: String? = null,
    val categoryName: String? = null,
    val trip: String? = null,
    val place: String? = null,
    val day: String? = null,
    val period: String? = null,
    val search: Boolean = false,
)
