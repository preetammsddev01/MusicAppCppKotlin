package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.model.TrackEntity
import com.example.ui.components.AddToPlaylistBottomSheet
import com.example.ui.components.MiniPlayerBar
import com.example.ui.screens.EqualizerScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.NowPlayingOverlay
import com.example.ui.screens.SearchScreen
import com.example.ui.theme.AetherAudioTheme
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.StudioConsole
import com.example.ui.viewmodel.AppViewModelFactory
import com.example.ui.viewmodel.EqualizerViewModel
import com.example.ui.viewmodel.HomeViewModel
import com.example.ui.viewmodel.LibraryViewModel
import com.example.ui.viewmodel.PlayerViewModel
import com.example.ui.viewmodel.SearchViewModel
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data object SearchRoute

@Serializable
data object LibraryRoute

@Serializable
data object EqualizerRoute

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val app = application as AetherAudioApp
        val factory = AppViewModelFactory(app)

        setContent {
            val homeViewModel: HomeViewModel = viewModel(factory = factory)
            val searchViewModel: SearchViewModel = viewModel(factory = factory)
            val libraryViewModel: LibraryViewModel = viewModel(factory = factory)
            val playerViewModel: PlayerViewModel = viewModel(factory = factory)
            val equalizerViewModel: EqualizerViewModel = viewModel(factory = factory)

            val accentColors by playerViewModel.accentColors.collectAsStateWithLifecycle()

            AetherAudioTheme(
                dynamicAccent = accentColors.primaryAccent,
                dynamicSecondary = accentColors.secondaryGlow
            ) {
                NotificationPermissionEffect()
                AetherAudioConsumerShell(
                    homeViewModel = homeViewModel,
                    searchViewModel = searchViewModel,
                    libraryViewModel = libraryViewModel,
                    playerViewModel = playerViewModel,
                    equalizerViewModel = equalizerViewModel
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as AetherAudioApp).playbackController.connect()
    }

    override fun onStop() {
        (application as AetherAudioApp).playbackController.disconnect()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) {
            (application as AetherAudioApp).playbackController.cleanupTempCacheIfIdle()
        }
        super.onDestroy()
    }
}

@Composable
private fun NotificationPermissionEffect() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* Playback still functions cleanly */ }
    )
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private enum class ConsumerDestination(
    val route: Any,
    val labelRes: Int,
    val testTag: String
) {
    HOME(HomeRoute, R.string.nav_home, "nav_home"),
    SEARCH(SearchRoute, R.string.nav_search, "nav_search"),
    LIBRARY(LibraryRoute, R.string.nav_library, "nav_library"),
    EQUALIZER(EqualizerRoute, R.string.nav_equalizer, "nav_equalizer")
}

@Composable
private fun AetherAudioConsumerShell(
    homeViewModel: HomeViewModel,
    searchViewModel: SearchViewModel,
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    equalizerViewModel: EqualizerViewModel
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()

    var isNowPlayingExpanded by rememberSaveable { mutableStateOf(false) }
    var playlistTargetTrack by remember { mutableStateOf<TrackEntity?>(null) }

    val navigateTop = remember(navController) {
        { route: Any ->
            if (navController.currentBackStackEntry?.destination?.hasRoute(route::class) != true) {
                navController.navigate(route) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            containerColor = ObsidianBg,
            contentWindowInsets = WindowInsets(0)
        ) { _ ->
            Row(modifier = Modifier.fillMaxSize()) {
                if (useNavigationRail) {
                    NavigationRail(
                        containerColor = StudioConsole,
                        modifier = Modifier
                            .fillMaxHeight()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    ) {
                        ConsumerDestination.entries.forEach { dest ->
                            val selected = currentDestination?.hasRoute(dest.route::class) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTop(dest.route) },
                                icon = { DestinationIcon(dest = dest, selected = selected) },
                                label = { Text(stringResource(dest.labelRes)) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                ),
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .statusBarsPadding()
                ) {
                    val railMiniPlayerPadding = if (useNavigationRail && playerState.currentTrack != null) 88.dp else 0.dp
                    val bottomContentPadding = remember(railMiniPlayerPadding) {
                        PaddingValues(bottom = railMiniPlayerPadding)
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        NavHost(
                            navController = navController,
                            startDestination = HomeRoute,
                            enterTransition = { EnterTransition.None },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                            popExitTransition = { ExitTransition.None },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            composable<HomeRoute> {
                                val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
                                val openAlbum = remember(searchViewModel, navigateTop) {
                                    { albumName: String ->
                                        searchViewModel.openAlbum(albumName)
                                        navigateTop(SearchRoute)
                                    }
                                }
                                val openGenre = remember(searchViewModel, navigateTop) {
                                    { genre: String ->
                                        searchViewModel.selectGenre(genre)
                                        navigateTop(SearchRoute)
                                    }
                                }
                                HomeScreen(
                                    uiState = homeState,
                                    currentPlayingTrackId = playerState.currentTrack?.id,
                                    isPlaying = playerState.isPlaying,
                                    onRefresh = homeViewModel::refreshCatalog,
                                    onDismissError = homeViewModel::dismissError,
                                    onPlayTrack = homeViewModel::playTrack,
                                    onPlayAlbum = homeViewModel::playAlbum,
                                    onToggleLike = homeViewModel::toggleLike,
                                    onOpenAlbumInSearch = openAlbum,
                                    onOpenGenreInSearch = openGenre,
                                    bottomContentPadding = bottomContentPadding
                                )
                            }

                            composable<SearchRoute> {
                                val searchState by searchViewModel.uiState.collectAsStateWithLifecycle()
                                val addToPlaylist = remember {
                                    { track: TrackEntity -> playlistTargetTrack = track }
                                }
                                SearchScreen(
                                    uiState = searchState,
                                    currentPlayingTrackId = playerState.currentTrack?.id,
                                    isPlaying = playerState.isPlaying,
                                    onQueryChange = searchViewModel::updateQuery,
                                    onFilterSelect = searchViewModel::selectFilter,
                                    onSelectGenre = searchViewModel::selectGenre,
                                    onOpenAlbum = searchViewModel::openAlbum,
                                    onOpenArtist = searchViewModel::openArtist,
                                    onPlayTrack = searchViewModel::playTrack,
                                    onPlayAll = searchViewModel::playAll,
                                    onToggleLike = searchViewModel::toggleLike,
                                    onAddToPlaylist = addToPlaylist,
                                    bottomContentPadding = bottomContentPadding
                                )
                            }

                            composable<LibraryRoute> {
                                val libraryState by libraryViewModel.uiState.collectAsStateWithLifecycle()
                                val createPlaylist = remember(libraryViewModel) {
                                    { name: String -> libraryViewModel.createPlaylist(name) }
                                }
                                val addToPlaylist = remember {
                                    { track: TrackEntity -> playlistTargetTrack = track }
                                }
                                LibraryScreen(
                                    uiState = libraryState,
                                    currentPlayingTrackId = playerState.currentTrack?.id,
                                    isPlaying = playerState.isPlaying,
                                    onSelectTab = libraryViewModel::selectTab,
                                    onOpenDetail = libraryViewModel::openDetail,
                                    onCloseDetail = libraryViewModel::closeDetail,
                                    onCreatePlaylist = createPlaylist,
                                    onRenamePlaylist = libraryViewModel::renamePlaylist,
                                    onDeletePlaylist = libraryViewModel::deletePlaylist,
                                    onRemoveFromPlaylist = libraryViewModel::removeTrackFromPlaylist,
                                    onToggleLike = libraryViewModel::toggleLike,
                                    onAddToPlaylist = addToPlaylist,
                                    onPlayTrack = libraryViewModel::playTrack,
                                    onPlayAll = libraryViewModel::playAll,
                                    bottomContentPadding = bottomContentPadding
                                )
                            }

                            composable<EqualizerRoute> {
                                val eqState by equalizerViewModel.uiState.collectAsStateWithLifecycle()
                                EqualizerScreen(
                                    state = eqState,
                                    onToggleDsp = equalizerViewModel::toggleDspEnabled,
                                    onSelectPreset = equalizerViewModel::selectPreset,
                                    onBandGainChange = equalizerViewModel::updateBandGain,
                                    onBassBoostChange = equalizerViewModel::updateBassBoost,
                                    onVirtualizerChange = equalizerViewModel::updateVirtualizer,
                                    onResetFlat = equalizerViewModel::resetToFlat,
                                    onSaveCustomPreset = equalizerViewModel::saveCustomPreset,
                                    onDeleteCustomPreset = equalizerViewModel::deleteCustomPreset,
                                    bottomContentPadding = bottomContentPadding
                                )
                            }
                        }

                        if (useNavigationRail && playerState.currentTrack != null) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                            ) {
                                MiniPlayerBar(
                                    track = playerState.currentTrack!!,
                                    isPlaying = playerState.isPlaying,
                                    isBuffering = playerState.isBuffering,
                                    positionMsFlow = playerViewModel.positionMs,
                                    durationMs = playerState.durationMs,
                                    signalLabel = playerState.signalDisplayLabel,
                                    isHiRes = playerState.isHiResSignal,
                                    prepStatusMessage = playerState.prepStatusMessage,
                                    prepProgress = playerState.prepProgress,
                                    onExpand = { isNowPlayingExpanded = true },
                                    onToggleLike = playerViewModel::toggleCurrentTrackLike,
                                    onTogglePlayPause = playerViewModel::togglePlayPause,
                                    onSkipNext = playerViewModel::skipNext
                                )
                            }
                        }
                    }

                    if (!useNavigationRail) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                        ) {
                            if (!isNowPlayingExpanded) {
                                playerState.errorMessage?.let { err ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = ErrorCoral.copy(alpha = 0.16f),
                                        border = BorderStroke(1.dp, ErrorCoral.copy(alpha = 0.5f)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 4.dp)
                                            .testTag("mini_player_error_banner")
                                            .clickable(onClick = playerViewModel::dismissError)
                                    ) {
                                        Text(
                                            text = err,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = ErrorCoral,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            }
                            playerState.currentTrack?.let { activeTrack ->
                                MiniPlayerBar(
                                    track = activeTrack,
                                    isPlaying = playerState.isPlaying,
                                    isBuffering = playerState.isBuffering,
                                    positionMsFlow = playerViewModel.positionMs,
                                    durationMs = playerState.durationMs,
                                    signalLabel = playerState.signalDisplayLabel,
                                    isHiRes = playerState.isHiResSignal,
                                    prepStatusMessage = playerState.prepStatusMessage,
                                    prepProgress = playerState.prepProgress,
                                    onExpand = { isNowPlayingExpanded = true },
                                    onToggleLike = playerViewModel::toggleCurrentTrackLike,
                                    onTogglePlayPause = playerViewModel::togglePlayPause,
                                    onSkipNext = playerViewModel::skipNext
                                )
                            }

                            NavigationBar(
                                containerColor = StudioConsole,
                                windowInsets = WindowInsets(0)
                            ) {
                                ConsumerDestination.entries.forEach { dest ->
                                    val selected = currentDestination?.hasRoute(dest.route::class) == true
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { navigateTop(dest.route) },
                                        icon = { DestinationIcon(dest = dest, selected = selected) },
                                        label = { Text(stringResource(dest.labelRes)) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        modifier = Modifier.testTag(dest.testTag)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = isNowPlayingExpanded && playerState.currentTrack != null,
            enter = slideInVertically(animationSpec = tween(180)) { it },
            exit = slideOutVertically(animationSpec = tween(150)) { it }
        ) {
            val eqState by equalizerViewModel.uiState.collectAsStateWithLifecycle()
            NowPlayingOverlay(
                playbackState = playerState,
                positionMsFlow = playerViewModel.positionMs,
                eqState = eqState,
                onSelectEqPreset = equalizerViewModel::selectPreset,
                onCollapse = { isNowPlayingExpanded = false },
                onToggleLike = playerViewModel::toggleCurrentTrackLike,
                onAddToPlaylist = { track -> playlistTargetTrack = track },
                onTogglePlayPause = playerViewModel::togglePlayPause,
                onSeekTo = playerViewModel::seekTo,
                onSkipNext = playerViewModel::skipNext,
                onSkipPrevious = playerViewModel::skipPrevious,
                onToggleShuffle = playerViewModel::toggleShuffle,
                onCycleRepeat = playerViewModel::cycleRepeatMode,
                onSelectQueueIndex = playerViewModel::skipToQueueIndex,
                onOpenEqualizer = {
                    isNowPlayingExpanded = false
                    navigateTop(EqualizerRoute)
                },
                onDismissError = playerViewModel::dismissError
            )
        }

        playlistTargetTrack?.let { target ->
            val libraryState by libraryViewModel.uiState.collectAsStateWithLifecycle()
            AddToPlaylistBottomSheet(
                track = target,
                playlists = libraryState.playlists,
                onSelectPlaylist = { playlistId ->
                    libraryViewModel.addTrackToPlaylist(playlistId, target.id)
                },
                onCreatePlaylistAndAdd = { name ->
                    libraryViewModel.createPlaylist(name, initialTrackId = target.id)
                },
                onDismiss = { playlistTargetTrack = null }
            )
        }
    }
}

@Composable
private fun DestinationIcon(dest: ConsumerDestination, selected: Boolean) {
    val icon = when (dest) {
        ConsumerDestination.HOME -> if (selected) Icons.Filled.Home else Icons.Outlined.Home
        ConsumerDestination.SEARCH -> if (selected) Icons.Filled.Search else Icons.Outlined.Search
        ConsumerDestination.LIBRARY -> if (selected) Icons.Filled.LibraryMusic else Icons.Outlined.LibraryMusic
        ConsumerDestination.EQUALIZER -> if (selected) Icons.Filled.Equalizer else Icons.Outlined.Equalizer
    }
    Icon(imageVector = icon, contentDescription = stringResource(dest.labelRes))
}
