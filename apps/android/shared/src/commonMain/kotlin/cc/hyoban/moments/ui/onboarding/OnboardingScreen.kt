package cc.hyoban.moments.ui.onboarding

import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.ui.LocalDevice
import cc.hyoban.moments.ui.design.LocalDesktop
import cc.hyoban.moments.ui.components.BackHandler
import cc.hyoban.moments.ui.settings.ImportScreen
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.res.*
import org.jetbrains.compose.resources.StringResource
import cc.hyoban.moments.data.spec.Module
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cc.hyoban.moments.Setting
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.settings.ImportScreen
import cc.hyoban.moments.ui.settings.Segment
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.segmentColors
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private enum class Step { Welcome, Start, Connect, Import, Footprints, Background }

/**
 * 初次打开 (34): shown once, the first time the app opens with nothing set up. Where to start
 * (a site, another app's export, or nothing), then footprints, whose location is asked for only
 * there and only when turned on. Every step can be left undone; [onDone] when through.
 */
@Composable
fun OnboardingScreen(container: AppContainer, onDone: () -> Unit) {
    val device = LocalDevice.current
    val desktop = LocalDesktop.current
    var step by rememberSaveable { mutableStateOf(Step.Welcome) }
    val back = {
        step = when (step) {
            Step.Welcome, Step.Start -> Step.Welcome
            Step.Connect, Step.Import, Step.Footprints -> Step.Start
            Step.Background -> Step.Footprints
        }
    }
    // The footprints step only while the footprints are on (记录项与公开).
    val toFootprints: () -> Unit = { if (device.recordsFootprints && container.settings.isOn(Module.FOOTPRINTS)) step = Step.Footprints else onDone() }
    BackHandler(enabled = step != Step.Welcome, onBack = back)
    Box(Modifier.fillMaxSize().background(AppColors.page).testTag("onboarding")) {
        when (step) {
            Step.Welcome -> Welcome { step = Step.Start }
            Step.Start -> Start(back, onSite = { step = Step.Connect }, onImport = { step = Step.Import }, onBlank = toFootprints, desktop = desktop, more = device.onboardingStarts(container, toFootprints))
            Step.Connect -> Connect(container, back, toFootprints)
            // The 导入 page as it is in 我, with the way on at its foot; back is back to where to start.
            Step.Import -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { ImportScreen(container, onBack = back) }
                Button(
                    onClick = toFootprints,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.gutter).padding(top = Space.s, bottom = Space.xl).testTag("onboarding_import_next"),
                ) { Text(stringResource(Res.string.onboarding_continue)) }
            }
            // Recording the footprints is the phone's own, with what it asks of the system.
            Step.Footprints, Step.Background -> device.OnboardingFootprints(container, back, onDone)
        }
    }
}

/** A step's page of 初次打开: its large title over [content], the step's buttons kept at its foot; the phone's footprint steps use it too. */
@Composable
fun StepPage(title: String, onBack: () -> Unit, subtitle: String? = null, actions: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    SubPage(title, onBack, Modifier.background(AppColors.page)) {
        PageWidth(maxColumns = 1) { columns ->
            Column(Modifier.fillMaxSize().imePadding()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(top = LocalTopClearance.current).padding(horizontal = columns.margin).padding(bottom = Space.xl),
                ) {
                    // The step's line in full under its title, rather than cut short in the bar's subtitle.
                    if (subtitle != null) Text(
                        subtitle,
                        Modifier.padding(horizontal = Space.gutter).padding(bottom = Space.s),
                        style = AppTheme.typography.body,
                        color = tokens.tx2,
                    )
                    content()
                }
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = columns.margin + Space.gutter).padding(bottom = Space.xl),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                    content = actions,
                )
            }
        }
    }
}

@Composable
private fun Welcome(onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = Space.gutter)) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Preview()
        }
        Text(stringResource(Res.string.onboarding_name), Modifier.semantics { heading() }, style = AppTheme.typography.titlePage)
        Text(stringResource(if (LocalDesktop.current) Res.string.onboarding_line_desktop else Res.string.onboarding_line), Modifier.padding(top = Space.xs), style = AppTheme.typography.body, color = tokens.tx2)
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().padding(top = Space.xl, bottom = Space.xl).testTag("onboarding_start")) {
            Text(stringResource(Res.string.onboarding_start))
        }
    }
}

/** A few lines of a day as the timeline has it: what the app is, shown rather than said. */
@Composable
private fun ColumnScope.Preview() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            stringResource(Res.string.onboarding_preview_day),
            Modifier.background(tokens.fill, Corners.full).padding(horizontal = Space.s, vertical = 3.dp),
            style = AppTheme.typography.label,
            color = tokens.tx2,
        )
    }
    Mine(stringResource(Res.string.onboarding_preview_one), stringResource(Res.string.onboarding_preview_one_place), "17:40")
    Column(
        Modifier.widthIn(max = 300.dp).background(tokens.surface, AppTheme.shapes.large).padding(horizontal = Space.m, vertical = Space.s),
    ) {
        Record("18:30", AppIcons.Food, stringResource(Res.string.onboarding_preview_bill), "−¥86.00")
        Record("19:05", AppIcons.Pin, stringResource(Res.string.onboarding_preview_checkin), "")
    }
    Mine(stringResource(Res.string.onboarding_preview_two), stringResource(Res.string.onboarding_preview_two_place), "19:20")
}

@Composable
private fun ColumnScope.Mine(words: String, place: String, time: String) {
    Column(
        Modifier.align(Alignment.End).widthIn(max = 280.dp).background(tokens.fill, AppTheme.shapes.large).padding(horizontal = Space.m, vertical = Space.s),
    ) {
        Text(words, style = AppTheme.typography.body)
        Text("$place $time", style = AppTheme.typography.caption, color = tokens.tx3)
    }
}

@Composable
private fun Record(time: String, icon: ImageVector, words: String, amount: String) {
    Row(Modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(time, style = AppTheme.typography.caption, color = tokens.tx3)
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = tokens.tx2)
        Text(words, Modifier.weight(1f, fill = false), style = AppTheme.typography.body)
        if (amount.isNotEmpty()) Text(amount, style = AppTheme.typography.body)
    }
}

@Composable
private fun Start(onBack: () -> Unit, onSite: () -> Unit, onImport: () -> Unit, onBlank: () -> Unit, desktop: Boolean, more: List<Segment>) {
    StepPage(stringResource(Res.string.onboarding_where), onBack, actions = {}) {
        val row = { icon: ImageVector, title: StringResource, note: StringResource, tag: String, onClick: () -> Unit ->
            val segment: Segment = { shapes ->
                SegmentedListItem(
                    onClick = onClick,
                    shapes = shapes,
                    colors = segmentColors(),
                    leadingContent = { Icon(icon, contentDescription = null) },
                    supportingContent = { Text(stringResource(note)) },
                    trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    modifier = Modifier.testTag(tag),
                ) { Text(stringResource(title)) }
            }
            segment
        }
        // Importing only while a source's module is on (记录项与公开).
        // A computer keeps what is written on itself, and reads the phone's ledger and footprints from its backup.
        val device = LocalDevice.current
        val settings = LocalSettings.current
        val imports = settings.isOn(Module.CHECKIN) || device.importsMore(settings)
        SegmentedGroup(null, stringResource(if (desktop) Res.string.onboarding_where_note_desktop else Res.string.onboarding_where_note), listOfNotNull(
            row(AppIcons.Link, Res.string.onboarding_site, Res.string.onboarding_site_note, "onboarding_site", onSite),
        ) + more + listOfNotNull(
            row(AppIcons.Download, Res.string.onboarding_import, if (desktop) Res.string.onboarding_import_note_desktop else Res.string.onboarding_import_note, "onboarding_import", onImport).takeIf { imports },
            row(if (desktop) AppIcons.Laptop else AppIcons.Phone, Res.string.onboarding_blank, if (desktop) Res.string.onboarding_blank_note_desktop else Res.string.onboarding_blank_note, "onboarding_blank", onBlank),
        ))
    }
}

@Composable
private fun Connect(container: AppContainer, onBack: () -> Unit, onNext: () -> Unit) {
    val settings = container.settings
    var url by rememberSaveable { mutableStateOf(settings.apiUrl.value) }
    var token by rememberSaveable { mutableStateOf("") }
    var shown by rememberSaveable { mutableStateOf(false) }
    var badUrl by rememberSaveable { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    var reading by remember { mutableStateOf<Deferred<Result<Long>>?>(null) }
    LaunchedEffect(reading) {
        val job = reading ?: return@LaunchedEffect
        if (job.await().isFailure) {
            failed = true
            reading = null
        }
    }
    val busy = reading != null
    StepPage(stringResource(Res.string.onboarding_connect), onBack, actions = {
        if (busy) Column(Modifier.padding(bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Spinner(Modifier.size(18.dp))
                Text(stringResource(Res.string.onboarding_reading), style = AppTheme.typography.label)
            }
            Text(stringResource(Res.string.onboarding_reading_note), style = AppTheme.typography.caption, color = tokens.tx2)
        }
        Button(
            onClick = {
                if (busy) onNext()
                else if (Setting.normalize(url).toHttpUrlOrNull() == null) badUrl = true
                else {
                    failed = false
                    reading = container.connect(url, token)
                }
            },
            enabled = busy || (url.isNotBlank() && token.isNotBlank()),
            modifier = Modifier.fillMaxWidth().testTag("onboarding_connect"),
        ) { Text(stringResource(if (busy) Res.string.onboarding_continue else Res.string.onboarding_connect_action)) }
    }) {
        Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.l)) {
            OutlinedTextField(
                url,
                { url = it; badUrl = false; failed = false },
                Modifier.fillMaxWidth().testTag("onboarding_api_url"),
                enabled = !busy,
                label = { Text(stringResource(Res.string.onboarding_api_url)) },
                placeholder = { Text("https://") },
                isError = badUrl,
                supportingText = if (badUrl) { { Text(stringResource(Res.string.onboarding_bad_url)) } } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
            )
            OutlinedTextField(
                token,
                { token = it; failed = false },
                Modifier.fillMaxWidth().testTag("onboarding_api_token"),
                enabled = !busy,
                label = { Text(stringResource(Res.string.onboarding_api_token)) },
                trailingIcon = {
                    IconButton(onClick = { shown = !shown }) {
                        Icon(if (shown) AppIcons.EyeOff else AppIcons.Eye, contentDescription = null)
                    }
                },
                isError = failed,
                supportingText = if (failed) { { Text(stringResource(Res.string.onboarding_failed)) } } else null,
                visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
            )
            Text(stringResource(Res.string.onboarding_connect_note), Modifier.padding(horizontal = Space.xs), style = AppTheme.typography.caption, color = tokens.tx2)
        }
    }
}
