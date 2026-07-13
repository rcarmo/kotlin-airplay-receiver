package io.carmo.airplay.receiver

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class QuickSettingsUiState(
    val receiverName: String = "Receiver",
    val quality: String = "",
    val screenFit: String = "",
    val audioSync: String = "",
    val audioRoute: String = "",
    val security: String = "",
    val selectedAction: QuickSettingAction = QuickSettingAction.QUALITY
)

enum class QuickSettingAction {
    QUALITY,
    SCREEN_FIT,
    AUDIO_SYNC,
    SECURITY,
    RESTART_DISCOVERY,
    SETTINGS
}

@Composable
fun QuickSettingsOverlay(state: QuickSettingsUiState) {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 56.dp, vertical = 44.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = Color(0xF20A0F14),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                modifier = Modifier.widthIn(min = 620.dp, max = 980.dp)
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = state.receiverName,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Quick Settings",
                        color = Color(0xAAFFFFFF),
                        fontSize = 18.sp,
                        modifier = Modifier.padding(top = 4.dp),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))
                    QuickValue("Quality", state.quality)
                    QuickValue("Screen fit", state.screenFit)
                    QuickValue("Audio sync", state.audioSync)
                    QuickValue("Audio output", state.audioRoute)
                    QuickValue("Security", state.security)

                    Spacer(modifier = Modifier.height(22.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        QuickButton("Quality", state.selectedAction == QuickSettingAction.QUALITY)
                        QuickButton("Fit", state.selectedAction == QuickSettingAction.SCREEN_FIT)
                        QuickButton("Sync", state.selectedAction == QuickSettingAction.AUDIO_SYNC)
                        QuickButton("Security", state.selectedAction == QuickSettingAction.SECURITY, wide = true)
                        QuickButton("Restart", state.selectedAction == QuickSettingAction.RESTART_DISCOVERY, wide = true)
                        QuickButton("Settings", state.selectedAction == QuickSettingAction.SETTINGS, wide = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickValue(label: String, value: String) {
    Row(
        modifier = Modifier
            .widthIn(min = 520.dp, max = 780.dp)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = Color(0x99FFFFFF),
            fontSize = 17.sp,
            modifier = Modifier.width(170.dp)
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 17.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 540.dp)
        )
    }
}

@Composable
private fun QuickButton(text: String, selected: Boolean, wide: Boolean = false) {
    Button(
        onClick = {},
        colors = ButtonDefaults.buttonColors(
            backgroundColor = if (selected) Color(0xFF64717E) else Color(0xFF3E4852),
            contentColor = Color.White
        ),
        modifier = Modifier
            .width(if (wide) 118.dp else 96.dp)
            .height(50.dp)
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = if (selected) QuickAccent else Color.Transparent,
                shape = RoundedCornerShape(5.dp)
            )
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

private val QuickAccent = Color(0xFF23D18B)
