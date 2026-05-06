package com.manegow.onboarding

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.manegow.model.identity.AvatarId

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onFinished: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val name by viewModel.name.collectAsState()
    val avatar by viewModel.avatar.collectAsState()

    LaunchedEffect(uiState) {
        if (uiState is OnboardingUiState.Finished) {
            onFinished()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Crossfade(targetState = uiState, label = "onboarding_step") { state ->
                when (state) {
                    OnboardingUiState.Intro -> IntroStep(
                        onNext = viewModel::onNextClicked
                    )

                    OnboardingUiState.Permissions -> PermissionsStep(
                        onNext = viewModel::onNextClicked
                    )

                    OnboardingUiState.AvatarSelection -> AvatarSelectionStep(
                        selected = avatar,
                        onAvatarSelected = viewModel::onAvatarSelected,
                        onNext = viewModel::onNextClicked
                    )

                    OnboardingUiState.Username -> UsernameStep(
                        name = name,
                        avatar = avatar,
                        onNameChanged = viewModel::onNameChanged,
                        onNext = viewModel::onNextClicked
                    )

                    OnboardingUiState.Finished -> Unit
                }
            }
        }
    }
}

@Composable
private fun IntroStep(
    onNext: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Bienvenido a DesChat",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Una app de mensajería local que funciona entre dispositivos cercanos usando Bluetooth, incluso sin internet.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onNext) {
            Text("Continuar")
        }
    }
}

@Composable
private fun PermissionsStep(
    onNext: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Permisos necesarios",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "DesChat necesita Bluetooth para descubrir personas cercanas, conectar dispositivos y enviar mensajes. También puede pedir notificaciones para avisarte cuando recibas mensajes.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Tus datos se quedan en tu dispositivo y el avatar se guarda localmente.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onNext) {
            Text("Entendido")
        }
    }
}

@Composable
private fun AvatarSelectionStep(
    selected: AvatarId,
    onAvatarSelected: (AvatarId) -> Unit,
    onNext: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Elige tu avatar",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Son ligeros y se dibujan dentro de la app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AvatarId.entries.forEach { avatar ->
                AvatarOption(
                    avatarId = avatar,
                    selected = avatar == selected,
                    onClick = { onAvatarSelected(avatar) }
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onNext) {
            Text("Continuar")
        }
    }
}

@Composable
private fun AvatarOption(
    avatarId: AvatarId,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(80.dp)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                },
                shape = RoundedCornerShape(20.dp)
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        FaceAvatar(
            avatarId = avatarId,
            modifier = Modifier.size(52.dp)
        )
    }
}

@Composable
fun FaceAvatar(
    avatarId: AvatarId,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val faceColor = Color(0xFFFFC857)
        val eyeColor = Color(0xFF1F2937)
        val mouthColor = Color(0xFF1F2937)
        val stroke = size.minDimension * 0.06f
        val radius = size.minDimension / 2f

        drawCircle(
            color = faceColor,
            radius = radius
        )

        val eyeY = size.height * 0.38f
        val leftEyeX = size.width * 0.33f
        val rightEyeX = size.width * 0.67f

        when (avatarId) {
            AvatarId.COOL -> {
                drawLine(
                    color = eyeColor,
                    start = Offset(size.width * 0.2f, eyeY),
                    end = Offset(size.width * 0.8f, eyeY),
                    strokeWidth = stroke * 1.3f,
                    cap = StrokeCap.Round
                )
                drawRect(
                    color = eyeColor,
                    topLeft = Offset(size.width * 0.18f, eyeY - stroke * 1.4f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.22f, stroke * 2.8f)
                )
                drawRect(
                    color = eyeColor,
                    topLeft = Offset(size.width * 0.60f, eyeY - stroke * 1.4f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.22f, stroke * 2.8f)
                )
            }

            AvatarId.WINK -> {
                drawCircle(color = eyeColor, radius = stroke * 0.9f, center = Offset(leftEyeX, eyeY))
                drawLine(
                    color = eyeColor,
                    start = Offset(rightEyeX - stroke, eyeY),
                    end = Offset(rightEyeX + stroke, eyeY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }

            else -> {
                drawCircle(color = eyeColor, radius = stroke * 0.9f, center = Offset(leftEyeX, eyeY))
                drawCircle(color = eyeColor, radius = stroke * 0.9f, center = Offset(rightEyeX, eyeY))
            }
        }

        when (avatarId) {
            AvatarId.HAPPY -> {
                drawArc(
                    color = mouthColor,
                    startAngle = 20f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.28f, size.height * 0.45f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.44f, size.height * 0.28f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }

            AvatarId.SAD -> {
                drawArc(
                    color = mouthColor,
                    startAngle = 200f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.28f, size.height * 0.58f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.44f, size.height * 0.20f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }

            AvatarId.SURPRISED -> {
                drawCircle(
                    color = mouthColor,
                    radius = stroke * 1.5f,
                    center = Offset(size.width * 0.5f, size.height * 0.68f)
                )
            }

            AvatarId.ANGRY -> {
                drawLine(
                    color = mouthColor,
                    start = Offset(size.width * 0.24f, size.height * 0.26f),
                    end = Offset(size.width * 0.40f, size.height * 0.32f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = mouthColor,
                    start = Offset(size.width * 0.76f, size.height * 0.26f),
                    end = Offset(size.width * 0.60f, size.height * 0.32f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = mouthColor,
                    start = Offset(size.width * 0.34f, size.height * 0.72f),
                    end = Offset(size.width * 0.66f, size.height * 0.72f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }

            AvatarId.COOL -> {
                drawLine(
                    color = mouthColor,
                    start = Offset(size.width * 0.34f, size.height * 0.70f),
                    end = Offset(size.width * 0.66f, size.height * 0.70f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }

            AvatarId.WINK -> {
                drawArc(
                    color = mouthColor,
                    startAngle = 15f,
                    sweepAngle = 120f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.32f, size.height * 0.50f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.34f, size.height * 0.22f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
    }
}

@Composable
private fun UsernameStep(
    name: String,
    avatar: AvatarId,
    onNameChanged: (String) -> Unit,
    onNext: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FaceAvatar(
            avatarId = avatar,
            modifier = Modifier.size(88.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Escoge tu nombre",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onNameChanged,
            label = { Text("Tu username o apodo") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onNext,
            enabled = name.isNotBlank()
        ) {
            Text("Entrar a DesChat")
        }
    }
}