package app.sillon.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.sillon.SillonApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var connection: PlayerConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
                .launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO))
        }
        val app = application as SillonApp
        setContent {
            SillonTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    var signedIn by remember { mutableStateOf(app.settings.isSignedIn) }
                    if (!signedIn) {
                        LoginScreen(app) { signedIn = true }
                    } else {
                        val player = remember { connection ?: PlayerConnection(this, app.api, app.musicAssistant).also { connection = it } }
                        MainScaffold(app, player)
                    }
                }
            }
        }
    }
}

@Composable
private fun MainScaffold(app: SillonApp, player: PlayerConnection) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val showPlayer = remember { mutableStateOf(false) }
    val toast = remember { mutableStateOf<String?>(null) }
    val ctx = remember { AppContext(app, player, nav, scope, showPlayer, toast) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(toast.value) {
        toast.value?.let { snackbar.showSnackbar(it); toast.value = null }
    }
    // Follow the download arrows: once à la carte has finished, the song is in the library
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            if (Downloads.states.values.none { it == DownloadState.Pending }) continue
            runCatching { app.api.jobs() }.onSuccess { jobs ->
                for (j in jobs) {
                    val key = j.songId ?: j.albumId ?: continue
                    if (Downloads.states[key] != DownloadState.Pending) continue
                    when (j.status) {
                        "done" -> Downloads.states[key] = DownloadState.InLibrary
                        "failed", "cancelled" -> { Downloads.states.remove(key); toast.value = "Échec du téléchargement : ${j.albumTitle ?: ""}" }
                    }
                }
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalApp provides ctx) {
        BackHandler(enabled = showPlayer.value) { showPlayer.value = false }
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    Column {
                        MiniPlayer()
                        val current by nav.currentBackStackEntryAsState()
                        val route = current?.destination?.route ?: "home"
                        val tab = when {
                            route == "search" || route.startsWith("catalog") -> "search"
                            route == "home" -> "home"
                            else -> "library"
                        }
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            listOf(
                                Triple("home", "Écouter", Icons.Filled.PlayCircle),
                                Triple("library", "Bibliothèque", Icons.Filled.LibraryMusic),
                                Triple("search", "Recherche", Icons.Filled.Search),
                            ).forEach { (r, label, icon) ->
                                NavigationBarItem(
                                    selected = tab == r,
                                    onClick = { nav.navigate(r) { popUpTo("home"); launchSingleTop = true } },
                                    icon = { Icon(icon, null) }, label = { Text(label) },
                                    colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.surface),
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
                    composable("home") { HomeScreen() }
                    composable("library") { LibraryScreen() }
                    composable("search") { SearchScreen() }
                    composable("albums") { AlbumsScreen() }
                    composable("artists") { ArtistsScreen() }
                    composable("playlists") { PlaylistsScreen() }
                    composable("songs") { SongsScreen() }
                    composable("starred") { StarredScreen() }
                    composable("album/{id}") { AlbumScreen(it.arguments?.getString("id") ?: "") }
                    composable("artist/{id}") { ArtistScreen(it.arguments?.getString("id") ?: "") }
                    composable("playlist/{id}") { PlaylistScreen(it.arguments?.getString("id") ?: "") }
                    composable("catalog-album/{id}") { CatalogAlbumScreen(it.arguments?.getString("id") ?: "") }
                }
            }
            AnimatedVisibility(showPlayer.value, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                NowPlayingScreen { showPlayer.value = false }
            }
        }
    }
}

@Composable
private fun LoginScreen(app: SillonApp, onDone: () -> Unit) {
    var server by remember { mutableStateOf(app.settings.serverUrl.ifEmpty { "https://" }) }
    var user by remember { mutableStateOf(app.settings.userName.ifEmpty { "admin" }) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        Text("Sillon", style = MaterialTheme.typography.displaySmall)
        Text("Connecte-toi à ton serveur", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(server, { server = it.trim() }, label = { Text("Adresse du serveur") }, singleLine = true,
            supportingText = { Text("Ton domaine, ou http://IP-DU-SERVEUR:4747 à la maison") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it.trim() }, label = { Text("Utilisateur") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Mot de passe") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = {
                busy = true; error = null
                scope.launch {
                    runCatching { app.api.signIn(server, user, password) }
                        .onSuccess { onDone() }.onFailure { error = it.message ?: "Connexion impossible" }
                    busy = false
                }
            },
            enabled = !busy && server.length > 8 && user.isNotEmpty() && password.isNotEmpty(),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text(if (busy) "Connexion…" else "Se connecter") }
    }
}
