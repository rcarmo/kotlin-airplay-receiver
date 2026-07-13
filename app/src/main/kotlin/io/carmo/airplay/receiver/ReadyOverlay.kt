package io.carmo.airplay.receiver

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ReadyUiState(
    val title: String = "AirPlay Receiver",
    val deviceName: String = "Receiver",
    val status: String = "Ready to AirPlay",
    val connectionHint: String = "",
    val qualitySummary: String = "",
    val securitySummary: String = "",
    val settingsHint: String = "",
    val settingsButtonText: String = "Settings",
    val quickButtonText: String = "Quick",
    val helpButtonText: String = "Help",
    val selectedAction: ReadyAction = ReadyAction.SETTINGS,
    val clockText: String = "",
    val clockVisible: Boolean = false,
    val clockOffsetX: Float = 0f,
    val clockOffsetY: Float = 0f
)

enum class ReadyAction {
    SETTINGS,
    QUICK,
    HELP
}

@Composable
fun ReadyOverlay(
    state: ReadyUiState
) {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 56.dp, vertical = 44.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = Color(0xF0061322),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0x40FFFFFF)),
                modifier = Modifier.widthIn(min = 520.dp, max = 920.dp)
            ) {
                Column(
                    modifier = Modifier.padding(
                        start = 34.dp,
                        top = 28.dp,
                        end = 34.dp,
                        bottom = 28.dp
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (state.clockVisible) {
                        Text(
                            text = state.clockText,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 48.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.graphicsLayer {
                                translationX = state.clockOffsetX
                                translationY = state.clockOffsetY
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Text(
                        text = state.title,
                        color = Color.White,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Light,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = state.deviceName,
                        color = Color(0xCCFFFFFF),
                        fontSize = 24.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .widthIn(max = 760.dp)
                    )

                    Text(
                        text = state.status,
                        color = Color(0xAAFFFFFF),
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp)
                    )

                    Text(
                        text = state.connectionHint,
                        color = Color(0x88FFFFFF),
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .widthIn(max = 760.dp)
                    )

                    if (state.qualitySummary.isNotBlank()) {
                        Text(
                            text = state.qualitySummary,
                            color = Color(0x88FFFFFF),
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    if (state.securitySummary.isNotBlank()) {
                        Text(
                            text = state.securitySummary,
                            color = Color(0x66FFFFFF),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.padding(top = 22.dp)
                    ) {
                        ReadyActionButton(
                            text = state.settingsButtonText,
                            selected = state.selectedAction == ReadyAction.SETTINGS
                        )
                        ReadyActionButton(
                            text = state.quickButtonText,
                            selected = state.selectedAction == ReadyAction.QUICK
                        )
                        ReadyActionButton(
                            text = state.helpButtonText,
                            selected = state.selectedAction == ReadyAction.HELP
                        )
                    }

                    if (state.settingsHint.isNotBlank()) {
                        Text(
                            text = state.settingsHint,
                            color = Color(0x66FFFFFF),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyActionButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(132.dp)
            .height(52.dp)
            .background(
                color = if (selected) Color(0xFF64717E) else Color(0xFF46505A),
                shape = RoundedCornerShape(5.dp)
            )
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = if (selected) ReadyAccent else Color.Transparent,
                shape = RoundedCornerShape(5.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private val ReadyAccent = Color(0xFF23D18B)
