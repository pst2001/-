package com.example.ttsapp.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ttsapp.R
import com.example.ttsapp.model.PlaybackStatus
import com.example.ttsapp.model.TtsLanguage
import com.example.ttsapp.model.TtsUiState
import java.util.Locale

/**
 * 单页布局：标题栏 → 文本输入卡片 → 参数卡片 → 状态卡片 → 底部固定播放栏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsScreen(viewModel: TtsViewModel = viewModel(factory = TtsViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // 一次性提示（Snackbar）：不做成 UiState 字段，避免旋转屏幕后重复弹出
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TtsEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        // 键盘弹出时整体上移，保证底部播放栏不被输入法挡住
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    TextButton(onClick = { openTtsSettings(context) }) {
                        Text(stringResource(R.string.action_voice_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        bottomBar = {
            PlaybackBar(
                state = state,
                onPlay = viewModel::play,
                onPause = viewModel::pause,
                onResume = viewModel::resume,
                onStop = viewModel::stop
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!state.isReady) {
                InitializingBanner()
            }
            if (state.needInstallVoice) {
                VoiceMissingBanner(onOpenSettings = { openTtsSettings(context) })
            }
            TextInputCard(
                text = state.text,
                onTextChange = viewModel::onTextChange
            )
            ParameterCard(
                state = state,
                onRateChange = viewModel::onSpeechRateChange,
                onPitchChange = viewModel::onPitchChange,
                onParamChangeFinished = viewModel::onParameterChangeFinished,
                onLanguageSelected = viewModel::onLanguageSelected
            )
            StatusCard(state = state)
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

// ------------------------------------------------------------------ 文本输入

@Composable
private fun TextInputCard(
    text: String,
    onTextChange: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.section_text),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.text_placeholder)) },
                minLines = 6,
                maxLines = 10,
                textStyle = MaterialTheme.typography.bodyLarge,
                shape = MaterialTheme.shapes.medium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.char_count, text.length),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = { onTextChange("") },
                    enabled = text.isNotEmpty()
                ) {
                    Text(stringResource(R.string.action_clear))
                }
            }
        }
    }
}

// -------------------------------------------------------------------- 参数区

@Composable
private fun ParameterCard(
    state: TtsUiState,
    onRateChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onParamChangeFinished: () -> Unit,
    onLanguageSelected: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.section_params),
                style = MaterialTheme.typography.titleMedium
            )
            SliderRow(
                label = stringResource(R.string.label_speech_rate),
                value = state.speechRate,
                valueText = stringResource(
                    R.string.value_rate_format,
                    formatOneDecimal(state.speechRate)
                ),
                min = TtsUiState.MIN_SPEECH_RATE,
                max = TtsUiState.MAX_SPEECH_RATE,
                onValueChange = onRateChange,
                onValueChangeFinished = onParamChangeFinished
            )
            SliderRow(
                label = stringResource(R.string.label_pitch),
                value = state.pitch,
                valueText = formatOneDecimal(state.pitch),
                min = TtsUiState.MIN_PITCH,
                max = TtsUiState.MAX_PITCH,
                onValueChange = onPitchChange,
                onValueChangeFinished = onParamChangeFinished
            )
            Spacer(modifier = Modifier.height(4.dp))
            LanguageSelector(
                languages = state.languages,
                selectedTag = state.selectedLanguageTag,
                enabled = state.isReady && state.languages.isNotEmpty(),
                onSelected = onLanguageSelected
            )
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueText: String,
    min: Float,
    max: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            valueRange = min..max,
            // 0.5..2.0 步进 0.1 共 16 个刻度，中间可停靠点 = 16 - 2
            steps = 14,
            onValueChangeFinished = onValueChangeFinished
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSelector(
    languages: List<TtsLanguage>,
    selectedTag: String?,
    enabled: Boolean,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = languages.firstOrNull { it.tag == selectedTag }?.label
        ?: stringResource(R.string.language_auto)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.label_language)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            languages.forEach { language ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = language.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelected(language.tag)
                    }
                )
            }
        }
    }
}

// -------------------------------------------------------------------- 状态区

@Composable
private fun StatusCard(state: TtsUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.section_status),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = statusText(state),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = statusColor(state)
                )
            }
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(
                        R.string.progress_chars,
                        state.spokenChars,
                        state.totalChars
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = state.engineLabel?.let { stringResource(R.string.engine_label, it) }
                        ?: stringResource(R.string.engine_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
    }
}

@Composable
private fun statusText(state: TtsUiState): String = when {
    !state.isReady -> stringResource(R.string.status_initializing)
    state.status == PlaybackStatus.PLAYING -> stringResource(R.string.status_playing)
    state.status == PlaybackStatus.PAUSED -> stringResource(R.string.status_paused)
    state.status == PlaybackStatus.FINISHED -> stringResource(R.string.status_finished)
    else -> stringResource(R.string.status_idle)
}

@Composable
private fun statusColor(state: TtsUiState): Color = when {
    !state.isReady -> MaterialTheme.colorScheme.onSurfaceVariant
    state.status == PlaybackStatus.PLAYING -> MaterialTheme.colorScheme.primary
    state.status == PlaybackStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
    state.status == PlaybackStatus.FINISHED -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

// -------------------------------------------------------------------- 底部栏

@Composable
private fun PlaybackBar(
    state: TtsUiState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val contentPadding = PaddingValues(horizontal = 6.dp, vertical = 12.dp)
            Button(
                onClick = onPlay,
                enabled = state.canPlay,
                modifier = Modifier.weight(1f),
                contentPadding = contentPadding
            ) {
                Text(stringResource(R.string.action_play), maxLines = 1)
            }
            FilledTonalButton(
                onClick = onPause,
                enabled = state.canPause,
                modifier = Modifier.weight(1f),
                contentPadding = contentPadding
            ) {
                Text(stringResource(R.string.action_pause), maxLines = 1)
            }
            FilledTonalButton(
                onClick = onResume,
                enabled = state.canResume,
                modifier = Modifier.weight(1f),
                contentPadding = contentPadding
            ) {
                Text(stringResource(R.string.action_resume), maxLines = 1)
            }
            OutlinedButton(
                onClick = onStop,
                enabled = state.canStop,
                modifier = Modifier.weight(1f),
                contentPadding = contentPadding
            ) {
                Text(stringResource(R.string.action_stop), maxLines = 1)
            }
        }
    }
}

// -------------------------------------------------------------------- 提示条

@Composable
private fun InitializingBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.status_initializing_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun VoiceMissingBanner(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.voice_missing_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.voice_missing_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.action_open_tts_settings))
            }
        }
    }
}

// -------------------------------------------------------------------- 工具函数

private fun formatOneDecimal(value: Float): String = String.format(Locale.US, "%.1f", value)

/**
 * 跳转系统「文字转语音」设置页。
 * 个别 ROM 没有这个入口，catch 后回退到系统设置首页，避免崩溃。
 */
private fun openTtsSettings(context: Context) {
    val candidates = listOf(
        Intent("com.android.settings.TTS_SETTINGS"),
        Intent(Settings.ACTION_SETTINGS)
    )
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            // 尝试下一个入口
        } catch (e: SecurityException) {
            // 部分 ROM 限制跳转，忽略
        }
    }
}
