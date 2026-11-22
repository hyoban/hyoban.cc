package cc.hyoban.moments.xiaohongshu

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.xiaohongshu_sign_in_title
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.components.SubPage
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * 登录小红书: the site's own pages, under the page's back circle, for the owner to sign in as they
 * do on a computer. Once the page says they are, it goes back by itself; leaving earlier keeps
 * what it last saw.
 */
@Composable
fun XiaohongshuSignInScreen(xiaohongshu: Xiaohongshu, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = remember {
        WebView(context).also { view ->
            Xiaohongshu.configure(view)
            view.loadUrl(XiaohongshuPage.HOME)
        }
    }
    DisposableEffect(view) {
        onDispose {
            android.webkit.CookieManager.getInstance().flush()
            view.stopLoading()
            view.destroy()
        }
    }
    // Asked of the page every second and a half; signed out to signed in goes back, and so does
    // finding the owner signed in when the app had them out.
    LaunchedEffect(view) {
        var wasOut = !xiaohongshu.signedIn.value
        while (true) {
            delay(1_500)
            when (Xiaohongshu.evaluate(view, XiaohongshuPage.SIGNED_IN_SCRIPT).let(XiaohongshuPage::unquoted)) {
                "yes" -> {
                    xiaohongshu.noteSignedIn(true)
                    if (wasOut) {
                        android.webkit.CookieManager.getInstance().flush()
                        onBack()
                        break
                    }
                }
                "no" -> {
                    wasOut = true
                    xiaohongshu.noteSignedIn(false)
                }
            }
        }
    }
    // The site drawn beside the page rather than in it: the page's scroll edge records what it holds
    // to blur it, and a WebView recorded so every frame runs out of tile memory and flickers blank.
    // It starts where the page's content would, under the title, so nothing passes under the bar.
    var clearance by remember { mutableStateOf(0.dp) }
    Box(Modifier.fillMaxSize()) {
        SubPage(stringResource(Res.string.xiaohongshu_sign_in_title), onBack, Modifier.background(AppColors.page)) {
            val top = LocalTopClearance.current
            SideEffect { clearance = top }
        }
        AndroidView(
            factory = { view },
            modifier = Modifier
                .fillMaxSize()
                .padding(top = clearance)
                .navigationBarsPadding()
                .testTag("xiaohongshu_sign_in"),
        )
    }
}
