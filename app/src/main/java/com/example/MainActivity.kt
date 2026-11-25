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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.statusBarsPadding
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

            val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
            val eqState by equalizerViewModel.uiState.collectAsStateWithLifecycle()

            AetherAudioTheme(
                dynamicAccent = playerState.accentColor,
                dynamicSecondary = playerState.secondaryGlowColor
            ) {
                NotificationPermissionEffect()
                AetherAudioConsumerShell(
                    homeViewModel = homeViewModel,
                    searchViewModel = searchViewModel,
                    libraryViewModel = libraryViewModel,
                    playerViewModel = playerViewModel,
                    equalizerViewModel = equalizerViewModel,
                    isDspEnabled = eqState.isDspEnabled
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
    equalizerViewModel: EqualizerViewModel,
    isDspEnabled: Boolean
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val searchState by searchViewModel.uiState.collectAsStateWithLifecycle()
    val libraryState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val eqState by equalizerViewModel.uiState.collectAsStateWithLifecycle()

    var isNowPlayingExpanded by rememberSaveable { mutableStateOf(false) }
    var playlistTargetTrack by remember { mutableStateOf<TrackEntity?>(null) }

    fun navigateTop(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
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

                    Box(modifier = Modifier.weight(1f)) {
                        NavHost(
                            navController = navController,
                            startDestination = HomeRoute,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            composable<HomeRoute> {
                                HomeScreen(
                                    uiState = homeState,
                                    currentPlayingTrackId = playerState.currentTrack?.id,
                                    isPlaying = playerState.isPlaying,
                                    onRefresh = homeViewModel::refreshCatalog,
                                    onDismissError = homeViewModel::dismissError,
                                    onPlayTrack = homeViewModel::playTrack,
                                    onPlayAlbum = homeViewModel::playAlbum,
                                    onToggleLike = homeViewModel::toggleLike,
                                    onOpenAlbumInSearch = { albumName ->
                                        searchViewModel.openAlbum(albumName)
                                        navigateTop(SearchRoute)
                                    },
                                    onOpenGenreInSearch = { genre ->
                                        searchViewModel.selectGenre(genre)
                                        navigateTop(SearchRoute)
                                    },
                                    bottomContentPadding = PaddingValues(bottom = railMiniPlayerPadding)
                                )
                            }

                            composable<SearchRoute> {
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
                                    onAddToPlaylist = { track -> playlistTargetTrack = track },
                                    bottomContentPadding = PaddingValues(bottom = railMiniPlayerPadding)
                                )
                            }

                            composable<LibraryRoute> {
                                LibraryScreen(
                                    uiState = libraryState,
                                    currentPlayingTrackId = playerState.currentTrack?.id,
                                    isPlaying = playerState.isPlaying,
                                    onSelectTab = libraryViewModel::selectTab,
                                    onOpenDetail = libraryViewModel::openDetail,
                                    onCloseDetail = libraryViewModel::closeDetail,
                                    onCreatePlaylist = { name -> libraryViewModel.createPlaylist(name) },
                                    onRenamePlaylist = libraryViewModel::renamePlaylist,
                                    onDeletePlaylist = libraryViewModel::deletePlaylist,
                                    onRemoveFromPlaylist = libraryViewModel::removeTrackFromPlaylist,
                                    onToggleLike = libraryViewModel::toggleLike,
                                    onAddToPlaylist = { track -> playlistTargetTrack = track },
                                    onPlayTrack = libraryViewModel::playTrack,
                                    onPlayAll = libraryViewModel::playAll,
                                    bottomContentPadding = PaddingValues(bottom = railMiniPlayerPadding)
                                )
                            }

                            composable<EqualizerRoute> {
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
                                    bottomContentPadding = PaddingValues(bottom = railMiniPlayerPadding)
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
                                    positionMs = playerState.positionMs,
                                    durationMs = playerState.durationMs,
                                    signalLabel = playerState.signalDisplayLabel,
                                    isHiRes = playerState.isHiResSignal,
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
                            playerState.currentTrack?.let { activeTrack ->
                                MiniPlayerBar(
                                    track = activeTrack,
                                    isPlaying = playerState.isPlaying,
                                    isBuffering = playerState.isBuffering,
                                    positionMs = playerState.positionMs,
                                    durationMs = playerState.durationMs,
                                    signalLabel = playerState.signalDisplayLabel,
                                    isHiRes = playerState.isHiResSignal,
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
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            NowPlayingOverlay(
                playbackState = playerState,
                isDspEnabled = isDspEnabled,
                isNativeCppEngine = eqState.isNativeCppEngine,
                isBitExactBypass = eqState.isBitExactBypass,
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
