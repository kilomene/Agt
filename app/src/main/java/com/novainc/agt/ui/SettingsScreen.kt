package com.novainc.agt.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novainc.agt.core.AgtRuntime

/**
 * Settings: the tunnel token and the host identity. The token is never
 * shipped in the app and never leaves this phone: it is typed here once
 * and stored in the app-private settings file.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { AgtRuntime.get(context) }
    var settings by remember { mutableStateOf(runtime.store.loadSettings()) }
    var token by remember { mutableStateOf(settings.tunnelToken) }
    var anonKey by remember { mutableStateOf(settings.supabaseAnonKey) }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Text("Settings", color = AgtColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Host setup, kept on this phone only.", color = AgtColors.TextDim, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        AgtCard {
            Text("CLOUDFLARE TUNNEL TOKEN", color = AgtColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Paste the tunnel token once. The tunnel then connects as ${com.novainc.agt.core.AgtConfig.TUNNEL_HOSTNAME}. " +
                    "The token is stored only here and is never sent anywhere except to cloudflared.",
                color = AgtColors.TextPrimary, fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))
            AgtField(token, { token = it; saved = false }, "Tunnel token", placeholder = "Paste the token here")
            Spacer(Modifier.height(12.dp))
            GoldButton("Save token", onClick = {
                val s = runtime.store.loadSettings()
                runtime.store.saveSettings(s.copy(tunnelToken = token))
                settings = runtime.store.loadSettings()
                saved = true
            }, modifier = Modifier.fillMaxWidth())
            if (saved) {
                Spacer(Modifier.height(8.dp))
                Text("Saved. Restart the service for the tunnel to pick it up.", color = AgtColors.Emerald, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        AgtCard {
            Text("SUPABASE ANON KEY", color = AgtColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "The public anon key for the termux-agt project. It is safe to store here: " +
                    "the tables are protected by row level security. Without it the Supabase " +
                    "leg stays off and Phoenix cannot send jobs.",
                color = AgtColors.TextPrimary, fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))
            AgtField(anonKey, { anonKey = it; saved = false }, "Anon key", placeholder = "eyJhbGciOi...")
            Spacer(Modifier.height(12.dp))
            GoldButton("Save anon key", onClick = {
                val s = runtime.store.loadSettings()
                runtime.store.saveSettings(s.copy(supabaseAnonKey = anonKey))
                settings = runtime.store.loadSettings()
                saved = true
            }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        AgtCard {
            Text("HOST", color = AgtColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Device id: ${runtime.deviceId()}", color = AgtColors.TextPrimary, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text("Linux: ${if (runtime.proot.isInstalled()) "installed (${settings.rootfsVersion})" else "not installed"}", color = AgtColors.TextPrimary, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text("Firebase token: ${if (settings.firebaseToken.isNotEmpty()) "set" else "not set yet, Phoenix sends it"}", color = AgtColors.TextPrimary, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
        GhostButton("Back", onClick = onBack, modifier = Modifier.fillMaxWidth())
    }
}
