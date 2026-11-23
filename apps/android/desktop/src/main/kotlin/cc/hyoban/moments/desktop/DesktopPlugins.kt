package cc.hyoban.moments.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.footprints_title
import cc.hyoban.moments.res.me_ledger
import cc.hyoban.moments.res.me_things
import cc.hyoban.moments.res.occasions_title
import cc.hyoban.moments.ui.LedgerHome
import cc.hyoban.moments.ui.Occasions
import cc.hyoban.moments.ui.Things
import cc.hyoban.moments.ui.design.AppIcons
import org.jetbrains.compose.resources.StringResource

/**
 * What a module brings to the Mac alone, beside what it brings every device: its section in the
 * sidebar, by [key] (as `HERE_OPEN` and 启动时 name it), a destination among the others or, [mine],
 * under 我的. Listed while the module is on.
 */
internal class ModuleSection(
    val module: Module,
    val key: String,
    val label: StringResource,
    val icon: ImageVector,
    val route: Any,
    val mine: Boolean = false,
    /** Whether a route is one of its own pages, which [Page] draws. */
    val shows: (Any) -> Boolean = { false },
    /** The services the module gives on the Mac: its stores, read from the phone's backup. */
    val services: cc.hyoban.moments.plugins.Contributions.() -> Unit = {},
    val Page: @Composable (pages: DesktopPages, route: Any) -> Unit = { _, _ -> },
)

/** What a module's own pages on the Mac are opened with: going back, the ledger's ways, a day's page, and where what they show came from. */
internal class DesktopPages(
    val container: DesktopContainer,
    val back: () -> Unit,
    val ledgerNav: cc.hyoban.moments.ui.ledger.LedgerNav,
    val openDay: (String) -> Unit,
    /** Where the ledger and the footprints shown came from: the phone's backup, and when it was made. */
    val source: @Composable () -> String?,
)

/** The point the Mac's sidebar reads. */
internal object DesktopPoints {
    val sections = cc.hyoban.moments.plugins.Point<ModuleSection>("desktop-sections")
}

/** A module's part on the Mac alone: its sidebar section, and what it gives there. */
private class DesktopModule(val section: ModuleSection) : cc.hyoban.moments.plugins.Plugin {
    override val module get() = section.module

    override fun cc.hyoban.moments.plugins.Contributions.contribute() {
        add(DesktopPoints.sections, section)
        section.services(this)
    }
}

/** The Mac's own plugins, installed beside every device's as it starts, in the sidebar's order. */
internal object DesktopPlugins {
    val all: List<cc.hyoban.moments.plugins.Plugin> get() = modules.map(::DesktopModule) + PhoneBackupPlugin + NasMac

    /** The modules' sections, in the sidebar's order. */
    val sections: List<ModuleSection> get() = cc.hyoban.moments.plugins.Plugins.registry.all(DesktopPoints.sections)

    private val modules = listOf(
        ModuleSection(
            Module.FOOTPRINTS, "footprints", Res.string.footprints_title, AppIcons.Footprints, FootprintDays,
            shows = { it == FootprintDays },
            services = { provide(cc.hyoban.moments.plugins.Services.footprints) { (it as DesktopContainer).phoneBackup.footprints } },
        ) { pages, _ -> cc.hyoban.moments.ui.footprints.FootprintDaysScreen(pages.container, pages.source(), onDay = pages.openDay, onBack = pages.back) },
        ModuleSection(
            Module.LEDGER, "ledger", Res.string.me_ledger, AppIcons.Wallet, LedgerHome(), mine = true,
            shows = { it is LedgerHome || it is cc.hyoban.moments.ui.LedgerBill || it is cc.hyoban.moments.ui.LedgerAccount || it is cc.hyoban.moments.ui.ledger.LedgerFilter },
            services = { provide(cc.hyoban.moments.plugins.Services.ledger) { (it as DesktopContainer).phoneBackup.ledger } },
        ) { pages, route ->
            when (route) {
                is LedgerHome -> cc.hyoban.moments.ui.ledger.LedgerScreen(pages.container, pages.ledgerNav, route.tab, source = pages.source())
                is cc.hyoban.moments.ui.LedgerBill -> cc.hyoban.moments.ui.ledger.BillScreen(pages.container, route.id, pages.ledgerNav)
                is cc.hyoban.moments.ui.LedgerAccount -> cc.hyoban.moments.ui.ledger.AccountScreen(pages.container, route.id, pages.ledgerNav)
                is cc.hyoban.moments.ui.ledger.LedgerFilter -> cc.hyoban.moments.ui.ledger.LedgerListScreen(pages.container, route, pages.ledgerNav)
            }
        },
        ModuleSection(Module.THINGS, "things", Res.string.me_things, AppIcons.Laptop, Things(), mine = true),
        ModuleSection(
            Module.OCCASIONS, "occasions", Res.string.occasions_title, AppIcons.Gift, Occasions(), mine = true,
            shows = { it is Occasions },
        ) { pages, route -> cc.hyoban.moments.ui.occasions.OccasionsScreen(pages.container, onBack = pages.back, open = (route as Occasions).open) },
    )
}

/**
 * The phone's WebDAV backup, which the Mac reads its ledger and footprints from: where it is, its
 * group on 同步与来源, and a way to start from it in 初次打开.
 */
private object PhoneBackupPlugin : cc.hyoban.moments.plugins.Plugin, cc.hyoban.moments.plugins.SettingsGroup, cc.hyoban.moments.plugins.OnboardingStart {
    override val page = cc.hyoban.moments.plugins.SettingsPage.SYNC
    override val order = 10

    override fun cc.hyoban.moments.plugins.Contributions.contribute() {
        add(cc.hyoban.moments.plugins.Points.settingsGroups, this@PhoneBackupPlugin)
        add(cc.hyoban.moments.plugins.Points.onboardingStarts, this@PhoneBackupPlugin)
    }

    // Where the phone backs up to, read here for its ledger and footprints.
    @Composable
    override fun group(container: cc.hyoban.moments.AppContainer, nav: cc.hyoban.moments.plugins.SettingsPageNav) =
        cc.hyoban.moments.ui.settings.phoneBackupGroup((container as DesktopContainer).phoneBackup)

    // The phone's backup as a way to start: its WebDAV directory asked for, then read.
    @Composable
    override fun row(container: cc.hyoban.moments.AppContainer, next: () -> Unit) =
        cc.hyoban.moments.ui.settings.phoneBackupStart((container as DesktopContainer).phoneBackup, next)
}

/**
 * The NAS's part on the Mac: its client, its settings beside the app's, its password with the other
 * secrets, what is read off it in the user's caches and its originals kept in the Pictures folder's Here.
 */
private object NasMac : cc.hyoban.moments.plugins.Plugin {
    override fun cc.hyoban.moments.plugins.Contributions.contribute() {
        provide(cc.hyoban.moments.plugins.Services.nas) {
            val root = (it as DesktopContainer).root
            cc.hyoban.moments.nas.Nas(
                cc.hyoban.moments.FilePrefs(java.io.File(root, "nas.json")),
                cc.hyoban.moments.Token(java.io.File(root, "private/nas-password")),
                java.io.File(System.getProperty("user.home"), "Library/Caches/Here"),
                cc.hyoban.moments.nas.FolderKeep(java.io.File(System.getProperty("user.home"), "Pictures/Here")),
                clientId = "here-mac",
            )
        }
    }
}
