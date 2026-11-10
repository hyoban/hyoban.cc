package cc.hyoban.moments.ui.map

/** Where a [PickerMap] is to go, a new one each time, even to the same place. */
data class MapTarget(val latitude: Double, val longitude: Double, val zoom: Double, val serial: Int = targets++)

private var targets = 0

/** A town's streets: close enough to put a pin on the right one. */
const val PICK_ZOOM = 14.0
