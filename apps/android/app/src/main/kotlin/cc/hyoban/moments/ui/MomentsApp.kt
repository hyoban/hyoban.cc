package cc.hyoban.moments.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.ui.home.HomeScreen
import cc.hyoban.moments.ui.moments.MomentDetailScreen
import cc.hyoban.moments.ui.moments.MomentsScreen
import kotlinx.serialization.Serializable

@Serializable
object Home

@Serializable
object Moments

@Serializable
data class Detail(val uid: String)

/** The screens: the map at home, every Moment over it, and a Moment's page on top. The composer is a sheet. */
@Composable
fun MomentsApp(container: AppContainer) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Home) {
        composable<Home> {
            HomeScreen(container = container, onList = { navController.navigate(Moments) })
        }
        composable<Moments> {
            MomentsScreen(
                container = container,
                onOpen = { uid -> navController.navigate(Detail(uid)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<Detail> { entry ->
            MomentDetailScreen(
                container = container,
                uid = entry.toRoute<Detail>().uid,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
