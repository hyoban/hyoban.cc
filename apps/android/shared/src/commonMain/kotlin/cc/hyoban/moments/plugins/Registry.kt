package cc.hyoban.moments.plugins

import cc.hyoban.moments.data.spec.Module

/**
 * A place in the app the host pages read and the plugins add to: the photos' sources, the
 * timeline's records, the search's kinds, a Moment's sections. The host defines it and names the
 * kind of thing that goes in it; it never names a plugin.
 */
class Point<T : Any>(val name: String) {
    override fun toString() = name
}

/**
 * A part of the app: what it brings to the points and what it gives the other plugins. All of the
 * app's own parts are plugins, each registered once, the device's beside every device's.
 */
interface Plugin {
    /**
     * The module (记录项与公开's switch) whose switch it follows: while that is off, what it brings
     * is put away and its background work stops. Null for one no module holds (the NAS, the gallery).
     */
    val module: Module? get() = null

    /**
     * For one no module holds, the key of its own switch on this device (the NAS's and the camera's
     * 在相册里显示, at the top of their settings group); null for one always there. Off, what it
     * brings is put away as a module's is.
     */
    val switchKey: String? get() = null

    /** Puts what it brings in the points, once, as the registry is made. */
    fun Contributions.contribute()
}

/** What a plugin brings as the registry is made: what it adds to points, and the services it gives. */
class Contributions internal constructor() {
    internal val added = LinkedHashMap<Point<*>, MutableList<Any>>()
    internal val provided = LinkedHashMap<Service<*>, (cc.hyoban.moments.AppContainer) -> Any>()

    /** Adds [items] to [point], in their order, after what this plugin added there before. */
    fun <T : Any> add(point: Point<T>, vararg items: T) {
        added.getOrPut(point) { mutableListOf() }.addAll(items)
    }

    /** Gives [service], which [make] builds from the container it is first asked with; the container keeps it. */
    fun <T : Any> provide(service: Service<T>, make: (cc.hyoban.moments.AppContainer) -> T) {
        provided[service] = make
    }
}

/**
 * What one plugin gives itself and the others, asked for by its key rather than by naming the
 * plugin: a module's stores and clients (the ledger, the footprints, the NAS). Each is made once
 * per container, the first time it is asked for, and kept. Its own plugin, and what works whether
 * its module is on or off, asks with [Registry.own]; the other plugins and the host with
 * [Registry.service], which gives it only while the module of the plugin giving it is on, so each
 * says what it does without it.
 */
class Service<T : Any>(val name: String) {
    override fun toString() = name
}

/** One thing a plugin added to a point, with the plugin it is of. */
class Contribution<T : Any>(val plugin: Plugin, val item: T) {
    val module: Module? get() = plugin.module

    /** Whether it is shown with the modules [isOn] says are on, and the plugins' own switches [pluginOn] says are: both its module's and its own on, where it has them. */
    fun shownWith(isOn: (Module) -> Boolean, pluginOn: (String) -> Boolean = { true }): Boolean =
        (module?.let(isOn) ?: true) && (plugin.switchKey?.let(pluginOn) ?: true)
}

/**
 * Every plugin's contributions by point, made once from the plugins in their order: what a point
 * lists is in that order, each plugin's in the order it added them, unless the point's things carry
 * an order of their own.
 */
class Registry(val plugins: List<Plugin>) {
    private val byPoint: Map<Point<*>, List<Contribution<*>>>
    private val services: Map<Service<*>, Pair<Plugin, (cc.hyoban.moments.AppContainer) -> Any>>

    init {
        val points = LinkedHashMap<Point<*>, MutableList<Contribution<*>>>()
        val given = LinkedHashMap<Service<*>, Pair<Plugin, (cc.hyoban.moments.AppContainer) -> Any>>()
        plugins.forEach { plugin ->
            val brought = Contributions().apply { with(plugin) { contribute() } }
            brought.added.forEach { (point, items) -> points.getOrPut(point) { mutableListOf() } += items.map { Contribution(plugin, it) } }
            brought.provided.forEach { (service, make) ->
                check(service !in given) { "$service is given twice" }
                given[service] = plugin to make
            }
        }
        byPoint = points
        services = given
    }

    /** Everything added to [point], with whose it is. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> contributions(point: Point<T>): List<Contribution<T>> = byPoint[point].orEmpty() as List<Contribution<T>>

    /** Everything added to [point], whatever is on. */
    fun <T : Any> all(point: Point<T>): List<T> = contributions(point).map { it.item }

    /** What is added to [point] by the plugins shown with the modules [isOn] says are on. */
    fun <T : Any> on(point: Point<T>, isOn: (Module) -> Boolean): List<T> =
        contributions(point).filter { it.shownWith(isOn) }.map { it.item }

    /** What is added to [point] by the plugins shown with [settings]' modules and the plugins' own switches. */
    fun <T : Any> on(point: Point<T>, settings: cc.hyoban.moments.Settings): List<T> =
        contributions(point).filter { it.shownWith(settings::isOn, settings::isPluginOn) }.map { it.item }

    /**
     * [service] as [container] keeps it, made by the plugin giving it the first time it is asked
     * for, whatever modules are on: for that plugin's own code and what must work with its module off.
     */
    fun <T : Any> own(service: Service<T>, container: cc.hyoban.moments.AppContainer): T {
        val (_, make) = services[service] ?: error("No plugin here gives $service")
        return container.kept(service) { make(container) }
    }

    /** [service] as [container] keeps it, or null while the module of the plugin giving it is off, or no plugin here gives it. */
    fun <T : Any> service(service: Service<T>, container: cc.hyoban.moments.AppContainer, isOn: (Module) -> Boolean): T? {
        val (plugin, _) = services[service] ?: return null
        if (plugin.module?.let(isOn) == false) return null
        return own(service, container)
    }
}
