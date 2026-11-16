package com.novainc.agt.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novainc.agt.BuildConfig
import com.novainc.agt.core.AgtConfig
import com.novainc.agt.core.AgtRuntime

/**
 * Settings is a status page, not a setup form. The tunnel token and
 * the Supabase anon key live in the repo's GitHub secrets and are
 * baked into the build by CI; the Phoenix trust key ships inside the
 * app; the Firebase key is delivered by Phoenix on its own. There is
 * nothing here for the owner to paste, type, or configure.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { AgtRuntime.get(context) }
    var settings by remember { mutableStateOf(runtime.store.loadSettings()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Text("Settings", color = AgtColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Status only. This app sets itself up.", color = AgtColors.TextDim, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        AgtCard {
            Text("CONNECTION CONFIG", color = AgtColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "The tunnel token and the Supabase anon key live in the repo's GitHub secrets " +
                    "and are baked into the build. No pasting, no typing, nothing to set up.",
                color = AgtColors.TextPrimary, fontSize = 13.sp
            )
            Spacer(Modifier.height(10.dp))
            StatusRow(
                "Cloudflare tunnel token",
                if (AgtConfig.TUNNEL_TOKEN.isNotEmpty()) "Baked into this build" else "Missing from this build",
                if (AgtConfig.TUNNEL_TOKEN.isNotEmpty()) AgtColors.Emerald else AgtColors.Danger
            )
            StatusRow(
                "Supabase anon key",
                if (AgtConfig.SUPABASE_ANON_KEY.isNotEmpty()) "Baked into this build" else "Missing from this build",
                if (AgtConfig.SUPABASE_ANON_KEY.isNotEmpty()) AgtColors.Emerald else AgtColors.Danger
            )
            StatusRow(
                "Phoenix trust key",
                "Baked into this build",
                AgtColors.Emerald
            )
        }
        Spacer(Modifier.height(12.dp))
        AgtCard {
            Text("FIREBASE", color = AgtColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            StatusRow(
                "Realtime key",
                if (settings.firebaseToken.isNotEmpty()) "Delivered by Phoenix" else "On its way from Phoenix, lands on its own",
                if (settings.firebaseToken.isNotEmpty()) AgtColors.Emerald else AgtColors.Amber
            )
            StatusRow(
                "FCM push",
                if (settings.fcmToken.isNotEmpty()) "Registered, token on file with Phoenix" else "Registering with Firebase",
                if (settings.fcmToken.isNotEmpty()) AgtColors.Emerald else AgtColors.Amber
            )
        }
        Spacer(Modifier.height(12.dp))
        AgtCard {
            Text("HOST", color = AgtColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            StatusRow("Device id", runtime.deviceId(), AgtColors.TextPrimary)
            StatusRow(
                "Linux layer",
                if (runtime.linux.isInstalled()) "Installed (${settings.rootfsVersion})" else "Sets up automatically on first start",
                if (runtime.linux.isInstalled()) AgtColors.Emerald else AgtColors.Amber
            )
            StatusRow("App version", BuildConfig.VERSION_NAME, AgtColors.TextPrimary)
        }
        Spacer(Modifier.height(12.dp))
        AgtCard {
            Text("AGENT IDENTITY", color = AgtColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Pairing is optional: the Phoenix key is baked into this build, so signed jobs " +
                    "verify from the first minute. The code below only names the link if you want it named.",
                color = AgtColors.TextPrimary, fontSize = 13.sp
            )
            Spacer(Modifier.height(10.dp))
            StatusRow("Pairing code (optional)", runtime.pairingCode(), AgtColors.GoldSoft)
            Text("Device public key (Ed25519)", color = AgtColors.TextDim, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                try { runtime.identity.publicKeyBase64() } catch (e: Exception) { "" },
                color = AgtColors.TextPrimary, fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(16.dp))
        GhostButton("Back", onClick = onBack, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StatusRow(label: String, value: String, valueColor: Color) {
    // Both sides carry weight: an unweighted value takes its full
    // width first and crushes the label into a one-letter column (the
    // "FCM push" bug). Weighted, the label always keeps its share and
    // a long value wraps inside its own column, right aligned.
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label, color = AgtColors.TextDim, fontSize = 13.sp,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
        Text(
            value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End, modifier = Modifier.weight(1.3f)
        )
    }
}
