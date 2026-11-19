package com.mangatranslator.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mangatranslator.R
import com.mangatranslator.core.designsystem.LocalSnackbar
import com.mangatranslator.core.network.SupabaseConfig
import com.mangatranslator.core.theme.MtTheme
import com.mangatranslator.data.repositories.AuthRepository
import com.mangatranslator.features.admin.AdminActivateScreen
import com.mangatranslator.features.admin.AdminScreen
import com.mangatranslator.features.auth.ConfigMissingScreen
import com.mangatranslator.features.auth.SignInScreen
import com.mangatranslator.features.giftcodes.RedeemScreen
import com.mangatranslator.features.history.HistoryScreen
import com.mangatranslator.features.home.HomeScreen
import com.mangatranslator.features.library.LibraryScreen
import com.mangatranslator.features.library.WorkScreen
import com.mangatranslator.features.notifications.NotificationsScreen
import com.mangatranslator.features.profile.ProfileScreen
import com.mangatranslator.features.reader.ReaderScreen
import com.mangatranslator.features.settings.LegalScreen
import com.mangatranslator.features.settings.SettingsScreen
import com.mangatranslator.features.subscriptions.SubscriptionScreen
import com.mangatranslator.features.translate.JobScreen
import com.mangatranslator.features.translate.TranslateScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val TRANSLATE = "translate"
    const val HISTORY = "history"
    const val PROFILE = "profile"
    const val JOB = "job/{jobId}"
    const val READER = "reader/{jobId}"
    const val WORK = "work/{workId}"
    const val PLANS = "plans"
    const val REDEEM = "redeem"
    const val SETTINGS = "settings"
    const val ADMIN = "admin"
    const val ADMIN_ACTIVATE = "admin_activate"
    const val NOTIFICATIONS = "notifications"
    const val LEGAL = "legal/{kind}"

    fun job(id: String) = "job/$id"
    fun reader(id: String) = "reader/$id"
    fun work(id: String) = "work/$id"
    fun legal(kind: String) = "legal/$kind"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.nav_home, Icons.Outlined.Home),
    Tab(Routes.LIBRARY, R.string.nav_library, Icons.Outlined.CollectionsBookmark),
    Tab(Routes.TRANSLATE, R.string.nav_translate, Icons.Outlined.AddPhotoAlternate),
    Tab(Routes.HISTORY, R.string.nav_history, Icons.Outlined.History),
    Tab(Routes.PROFILE, R.string.nav_profile, Icons.Outlined.Person),
)

@HiltViewModel
class RootViewModel @Inject constructor(auth: AuthRepository) : ViewModel() {
    val session = auth.session
}

@Composable
fun AppRoot(vm: RootViewModel = hiltViewModel()) {
    val session by vm.session.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        when {
            !SupabaseConfig.isConfigured -> ConfigMissingScreen()
            session == null -> SignInScreen()
            else -> MainScaffold(snackbar)
        }
    }
}

/** Switch tabs keeping each tab's own back stack and scroll state. */
fun NavController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun MainScaffold(snackbar: SnackbarHostState) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val showBar = tabs.any { it.route == current }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.route,
                            onClick = { nav.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label), style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MtTheme.colors.textSecondary,
                                unselectedTextColor = MtTheme.colors.textSecondary,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(
                navController = nav,
                startDestination = Routes.HOME,
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
            ) {
                composable(Routes.HOME) { HomeScreen(nav) }
                composable(Routes.LIBRARY) { LibraryScreen(nav) }
                composable(Routes.TRANSLATE) { TranslateScreen(nav) }
                composable(Routes.HISTORY) { HistoryScreen(nav) }
                composable(Routes.PROFILE) { ProfileScreen(nav) }
                composable(Routes.JOB, listOf(navArgument("jobId") { type = NavType.StringType })) { JobScreen(nav) }
                composable(Routes.READER, listOf(navArgument("jobId") { type = NavType.StringType })) { ReaderScreen(nav) }
                composable(Routes.WORK, listOf(navArgument("workId") { type = NavType.StringType })) { WorkScreen(nav) }
                composable(Routes.PLANS) { SubscriptionScreen(nav) }
                composable(Routes.REDEEM) { RedeemScreen(nav) }
                composable(Routes.SETTINGS) { SettingsScreen(nav) }
                composable(Routes.ADMIN) { AdminScreen(nav) }
                composable(Routes.ADMIN_ACTIVATE) { AdminActivateScreen(nav) }
                composable(Routes.NOTIFICATIONS) { NotificationsScreen(nav) }
                composable(Routes.LEGAL, listOf(navArgument("kind") { type = NavType.StringType })) { LegalScreen(nav) }
            }
        }
    }
}
