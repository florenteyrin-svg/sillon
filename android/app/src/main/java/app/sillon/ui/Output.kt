package app.sillon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.sillon.data.MaPlayer
import kotlinx.coroutines.launch

/** "Écouter sur": this phone or a Music Assistant player; when one is playing, a small remote. */
object MaState {
    var activePlayer by mutableStateOf<MaPlayer?>(null)
}

@Composable
fun OutputSheet(onDismiss: () -> Unit) {
    val ctx = LocalApp.current
    val ma = ctx.app.musicAssistant
    val settings = ctx.app.settings
    val scope = rememberCoroutineScope()
    val state by ctx.player.state.collectAsState()
    var players by remember { mutableStateOf<List<MaPlayer>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(settings.maUrl) }
    var token by remember { mutableStateOf(settings.maToken) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        error = null
        runCatching { ma.players() }.onSuccess { players = it }.onFailure { error = it.message ?: "Music Assistant injoignable" }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Color(0xFF1C1C22))
                .clickable(enabled = false) {}.navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Écouter sur", color = Color.White, fontWeight = FontWeight.Bold, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF26262E)).clickable {
                    val p = MaState.activePlayer
                    if (p != null) scope.launch {
                        // take the music back: same song, same place
                        val now = runCatching { ma.nowPlaying(p.id) }.getOrNull()
                        runCatching { ma.command(p.id, "stop") }
                        ctx.player.resumeAt(now?.songId, ((now?.positionSec ?: 0.0) * 1000).toLong())
                        MaState.activePlayer = null
                    }
                    onDismiss()
                }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Icons.Filled.PhoneAndroid, null, tint = Color.White)
                Text(if (MaState.activePlayer != null) "Reprendre sur ce téléphone" else "Ce téléphone", color = Color.White, modifier = Modifier.weight(1f))
            }

            val list = players
            when {
                error != null -> {
                    Text("Music Assistant : $error", color = Color(0xFFFF8FA0))
                    OutlinedTextField(url, { url = it }, label = { Text("Adresse de Music Assistant") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(token, { token = it }, label = { Text("Jeton (si demandé)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Button(onClick = { settings.maUrl = url; settings.maToken = token; players = null; reload++ }, modifier = Modifier.fillMaxWidth()) {
                        Text("Enregistrer et réessayer")
                    }
                }
                list == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> list.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF26262E)).clickable(enabled = !busy && p.available) {
                            val ids = state.queue.drop(state.index).map { it.id }.filter { !it.startsWith("apple:") }
                            if (ids.isEmpty()) { ctx.toast.value = "Les titres Apple Music ne peuvent pas être envoyés"; return@clickable }
                            busy = true
                            scope.launch {
                                runCatching { ma.play(p.id, ids, state.positionMs / 1000) }
                                    .onSuccess { ctx.player.pause(); MaState.activePlayer = p; ctx.toast.value = "Lecture sur ${p.name}"; onDismiss() }
                                    .onFailure { ctx.toast.value = "Music Assistant : ${it.message}" }
                                busy = false
                            }
                        }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(Icons.Filled.Speaker, null, tint = Color.White)
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = Color.White)
                            Text(if (p.available) "Disponible" else "Éteint", color = Color.White.copy(alpha = 0.6f))
                        }
                        if (MaState.activePlayer?.id == p.id) {
                            IconButton(onClick = { scope.launch { runCatching { ma.command(p.id, "play_pause") } } }) {
                                Icon(if (true) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Lecture / pause", tint = Color.White)
                            }
                            IconButton(onClick = { scope.launch { runCatching { ma.command(p.id, "next") } } }) {
                                Icon(Icons.Filled.SkipNext, "Suivant", tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}
