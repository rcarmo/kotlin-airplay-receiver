package io.carmo.airplay.receiver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class OnboardingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initialName = ReceiverPreferences.customDeviceName(this)
            ?: DNSNotify.suggestedDeviceName(this)
        val initialSecurityMode = ReceiverPreferences.prefs(this)
            .getString(ReceiverPreferences.KEY_SECURITY_MODE, ReceiverPreferences.SECURITY_PIN_NEW_DEVICES)
            ?: ReceiverPreferences.SECURITY_PIN_NEW_DEVICES
        val initialQualityProfile = ReceiverPreferences.qualityProfile(this)

        setContent {
            OnboardingScreen(
                initialName = initialName,
                initialSecurityMode = initialSecurityMode,
                initialQualityProfile = initialQualityProfile,
                onCancel = ::finish,
                onDone = { receiverName, securityMode, qualityProfile ->
                    ReceiverPreferences.prefs(this).edit()
                        .putString(ReceiverPreferences.KEY_CUSTOM_DEVICE_NAME, receiverName)
                        .putString(ReceiverPreferences.KEY_SECURITY_MODE, securityMode)
                        .putString(ReceiverPreferences.KEY_QUALITY_PROFILE, qualityProfile)
                        .putBoolean(ReceiverPreferences.KEY_FIRST_RUN_COMPLETE, true)
                        .apply()
                    finish()
                }
            )
        }
    }
}

@Composable
private fun OnboardingScreen(
    initialName: String,
    initialSecurityMode: String,
    initialQualityProfile: String,
    onCancel: () -> Unit,
    onDone: (String, String, String) -> Unit
) {
    var step by remember { mutableStateOf(0) }
    var receiverName by remember { mutableStateOf(initialName) }
    var securityMode by remember { mutableStateOf(initialSecurityMode) }
    var qualityProfile by remember { mutableStateOf(initialQualityProfile) }
    val steps = Step.values()

    BackHandler {
        if (step > 0) {
            step -= 1
        } else {
            onCancel()
        }
    }

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(horizontal = 72.dp, vertical = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 1180.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "AirPlay Receiver",
                    color = Color.White,
                    fontSize = 44.sp,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "${step + 1} / ${steps.size}",
                    color = MutedText,
                    fontSize = 17.sp,
                    modifier = Modifier.padding(top = 14.dp)
                )
                Spacer(modifier = Modifier.height(36.dp))

                when (steps[step]) {
                    Step.WELCOME -> WelcomeStep()
                    Step.NAME -> NameStep(receiverName) { receiverName = it }
                    Step.SECURITY -> SecurityStep(securityMode) { securityMode = it }
                    Step.QUALITY -> QualityStep(qualityProfile) { qualityProfile = it }
                    Step.INSTRUCTIONS -> InstructionsStep(receiverName)
                }

                Spacer(modifier = Modifier.height(44.dp))
                NavigationRow(
                    backText = if (step == 0) "Cancel" else "Back",
                    nextText = if (step == steps.lastIndex) "Done" else if (step == 0) "Start" else "Next",
                    onBack = {
                        if (step == 0) {
                            onCancel()
                        } else {
                            step -= 1
                        }
                    },
                    onNext = {
                        if (step == steps.lastIndex) {
                            onDone(
                                receiverName.trim().ifBlank { initialName },
                                securityMode,
                                qualityProfile
                            )
                        } else {
                            step += 1
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    BodyText("Turn this Android TV into an AirPlay receiver for iPhone, iPad, and Mac.")
}

@Composable
private fun NameStep(receiverName: String, onNameChanged: (String) -> Unit) {
    BodyText("Name this TV as it should appear in AirPlay.")
    Spacer(modifier = Modifier.height(28.dp))
    OutlinedTextField(
        value = receiverName,
        onValueChange = onNameChanged,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 24.sp),
        colors = TextFieldDefaults.outlinedTextFieldColors(
            textColor = Color.White,
            cursorColor = Accent,
            focusedBorderColor = Accent,
            unfocusedBorderColor = MutedText,
            focusedLabelColor = Accent,
            unfocusedLabelColor = MutedText
        ),
        label = { Text("Receiver name") },
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 720.dp)
    )
}

@Composable
private fun SecurityStep(selected: String, onSelected: (String) -> Unit) {
    BodyText(
        "Choose the access behavior. PIN modes are saved now; AirPlay still uses the compatible open advertisement until native PIN verification is wired."
    )
    Spacer(modifier = Modifier.height(24.dp))
    OptionList(
        options = listOf(
            Option(
                ReceiverPreferences.SECURITY_PIN_NEW_DEVICES,
                "PIN for new devices",
                "Recommended. Saved now; native verification is still required for enforcement."
            ),
            Option(
                ReceiverPreferences.SECURITY_PIN_EVERY_SESSION,
                "PIN every session",
                "Saved now; this build keeps the compatible no-PIN transport."
            ),
            Option(
                ReceiverPreferences.SECURITY_OPEN,
                "Open - no pairing required",
                "Current enforced behavior on the local network."
            ),
            Option(
                ReceiverPreferences.SECURITY_TRUSTED_ONLY,
                "Trusted devices only",
                "Saved now; sender rejection needs native pairing identifiers."
            )
        ),
        selected = selected,
        onSelected = onSelected
    )
}

@Composable
private fun QualityStep(selected: String, onSelected: (String) -> Unit) {
    BodyText("Pick a starting quality profile. You can change this later in Settings.")
    Spacer(modifier = Modifier.height(24.dp))
    OptionList(
        options = listOf(
            Option(ReceiverPreferences.QUALITY_AUTO, "Auto", "Recommended. Choose based on display capability."),
            Option(ReceiverPreferences.QUALITY_LOW_LATENCY, "Low Latency", "720p with conservative latency."),
            Option(ReceiverPreferences.QUALITY_BEST, "Best Quality", "1080p for the cleanest source stream.")
        ),
        selected = selected,
        onSelected = onSelected
    )
}

@Composable
private fun InstructionsStep(receiverName: String) {
    BodyText(
        "iPhone or iPad\n" +
            "1. Connect to the same Wi-Fi as this TV.\n" +
            "2. Open Control Center.\n" +
            "3. Choose Screen Mirroring, then select $receiverName.\n\n" +
            "Mac\n" +
            "1. Connect to the same network.\n" +
            "2. Open Control Center or Displays.\n" +
            "3. Choose Screen Mirroring or AirPlay, then select $receiverName."
    )
}

@Composable
private fun BodyText(text: String) {
    Text(
        text = text,
        color = Color(0xDDFFFFFF),
        fontSize = 25.sp,
        lineHeight = 34.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 1120.dp)
    )
}

@Composable
private fun OptionList(
    options: List<Option>,
    selected: String,
    onSelected: (String) -> Unit
) {
    Column(
        modifier = Modifier.widthIn(max = 920.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        options.forEach { option ->
            OptionRow(
                option = option,
                selected = option.id == selected,
                onClick = { onSelected(option.id) }
            )
        }
    }
}

@Composable
private fun OptionRow(option: Option, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        focused -> Accent
        selected -> Color.White
        else -> Color(0x22FFFFFF)
    }
    val background = if (selected) Color(0xFF143B2D) else Color(0xFF151A20)

    Surface(
        color = background,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(if (focused) 3.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .border(2.dp, if (selected) Accent else MutedText, RoundedCornerShape(9.dp))
                    .background(if (selected) Accent else Color.Transparent, RoundedCornerShape(9.dp))
            )
            Spacer(modifier = Modifier.width(18.dp))
            Column {
                Text(
                    text = option.title,
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = option.subtitle,
                    color = MutedText,
                    fontSize = 16.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun NavigationRow(
    backText: String,
    nextText: String,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        FocusButton(text = backText, onClick = onBack)
        FocusButton(text = nextText, onClick = onNext)
    }
}

@Composable
private fun FocusButton(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = if (focused) Color(0xFF6A7280) else Color(0xFF4F545B),
            contentColor = Color.White
        ),
        modifier = Modifier
            .width(178.dp)
            .height(56.dp)
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) Accent else Color.Transparent,
                shape = RoundedCornerShape(5.dp)
            )
    ) {
        Text(text = text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    }
}

private data class Option(val id: String, val title: String, val subtitle: String)

private enum class Step {
    WELCOME,
    NAME,
    SECURITY,
    QUALITY,
    INSTRUCTIONS
}

private val Accent = Color(0xFF23D18B)
private val MutedText = Color(0x99FFFFFF)
