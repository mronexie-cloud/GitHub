package com.gameocr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameocr.app.R
import com.gameocr.app.data.FloatingSkill
import com.gameocr.app.data.Languages
import com.gameocr.app.data.LoopTriggerMode
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.data.Settings as AppSettings

/**
 * zlc fork: a simplified "pick a mode, pick languages, press Start" entry at the top of the home
 * screen. It only writes existing settings (floating-ball skill, loop trigger, language pair and
 * translator engine), so everything it sets can still be fine-tuned in the full settings screen.
 */
internal enum class QuickMode(val labelRes: Int, val descRes: Int, val badge: String, val color: Color) {
    FULL(R.string.quick_mode_full, R.string.quick_mode_full_desc, "A", Color(0xFF16A34A)),
    REGION(R.string.quick_mode_region, R.string.quick_mode_region_desc, "⛶", Color(0xFF2563EB)),
    AUTO(R.string.quick_mode_auto, R.string.quick_mode_auto_desc, "∞", Color(0xFFF59E0B)),
    INSTANT(R.string.quick_mode_instant, R.string.quick_mode_instant_desc, "⚡", Color(0xFF14B8A6)),
}

internal fun quickModeOf(skill: FloatingSkill, loopTrigger: LoopTriggerMode): QuickMode = when (skill) {
    FloatingSkill.WORD_SELECT -> QuickMode.REGION
    FloatingSkill.LOOP ->
        if (loopTrigger == LoopTriggerMode.FIXED_INTERVAL) QuickMode.INSTANT else QuickMode.AUTO
    else -> QuickMode.FULL
}

internal fun applyQuickMode(settings: AppSettings, mode: QuickMode): AppSettings = when (mode) {
    QuickMode.FULL -> settings.copy(floatingButtonSkill = FloatingSkill.FULL_SCREEN)
    QuickMode.REGION -> settings.copy(floatingButtonSkill = FloatingSkill.WORD_SELECT)
    QuickMode.AUTO -> settings.copy(
        floatingButtonSkill = FloatingSkill.LOOP,
        loopTriggerMode = LoopTriggerMode.SETTLED_PAGE,
    )
    QuickMode.INSTANT -> settings.copy(
        floatingButtonSkill = FloatingSkill.LOOP,
        loopTriggerMode = LoopTriggerMode.FIXED_INTERVAL,
    )
}

internal enum class QuickEngine(val labelRes: Int, val hintRes: Int) {
    OFFLINE(R.string.quick_engine_offline, R.string.quick_engine_offline_hint),
    ONLINE(R.string.quick_engine_online, R.string.quick_engine_online_hint),
    LLM(R.string.quick_engine_llm, R.string.quick_engine_llm_hint),
}

internal fun quickEngineOf(engine: TranslatorEngine): QuickEngine? = when (engine) {
    TranslatorEngine.GOOGLE_ML_KIT -> QuickEngine.OFFLINE
    TranslatorEngine.GOOGLE -> QuickEngine.ONLINE
    TranslatorEngine.OPENAI, TranslatorEngine.ANTHROPIC -> QuickEngine.LLM
    else -> null
}

/** The on-device engine needs a concrete source language, so "auto" falls back to English. */
internal fun applyQuickEngine(settings: AppSettings, engine: QuickEngine): AppSettings = when (engine) {
    QuickEngine.OFFLINE -> applyQuickOcr(
        settings.copy(
            translatorEngine = TranslatorEngine.GOOGLE_ML_KIT,
            sourceLang = quickOfflineSource(settings.sourceLang, settings.targetLang),
        )
    )
    QuickEngine.ONLINE -> settings.copy(translatorEngine = TranslatorEngine.GOOGLE)
    QuickEngine.LLM ->
        if (settings.translatorEngine == TranslatorEngine.ANTHROPIC) {
            settings
        } else {
            settings.copy(translatorEngine = TranslatorEngine.OPENAI)
        }
}

/**
 * zlc fork: automatic OCR guesses the script per region and, on mixed Chinese/Japanese screens,
 * sometimes hands CJK lines to the Latin recognizer (output like "70IVFTh-FEy-y"). When the user
 * has named the source language, read the screen with that language's recognizer instead.
 * Languages ML Kit has no recognizer for (Thai, Russian, ...) keep automatic OCR.
 */
internal fun quickOcrEngineFor(sourceLang: String): OcrEngineKind {
    val code = sourceLang.trim().lowercase()
    return when {
        code.startsWith("ja") -> OcrEngineKind.ML_KIT_JAPANESE
        code.startsWith("zh") -> OcrEngineKind.ML_KIT_CHINESE
        code.startsWith("ko") -> OcrEngineKind.ML_KIT_KOREAN
        code in QUICK_LATIN_CODES -> OcrEngineKind.ML_KIT_LATIN
        else -> OcrEngineKind.ML_KIT_AUTO
    }
}

/** Only touches the ML Kit choices, so a user-picked Paddle/cloud OCR engine is left alone. */
internal fun applyQuickOcr(settings: AppSettings): AppSettings {
    if (settings.ocrEngine !in QUICK_MLKIT_ENGINES) return settings
    val wanted = quickOcrEngineFor(settings.sourceLang)
    return if (wanted == settings.ocrEngine) settings else settings.copy(ocrEngine = wanted)
}

private val QUICK_MLKIT_ENGINES = setOf(
    OcrEngineKind.ML_KIT_AUTO,
    OcrEngineKind.ML_KIT_LATIN,
    OcrEngineKind.ML_KIT_JAPANESE,
    OcrEngineKind.ML_KIT_CHINESE,
    OcrEngineKind.ML_KIT_KOREAN,
)

private val QUICK_LATIN_CODES = setOf("en", "ms", "id", "vi", "fr", "de", "es", "it", "pt", "nl", "tl")

internal fun quickLlmKeyMissing(settings: AppSettings): Boolean =
    if (settings.translatorEngine == TranslatorEngine.ANTHROPIC) {
        settings.anthropicApiKey.isBlank()
    } else {
        settings.apiKey.isBlank()
    }

internal fun quickOfflineSource(sourceLang: String, targetLang: String): String {
    if (!sourceLang.equals(Languages.AUTO.code, ignoreCase = true) && sourceLang.isNotBlank()) {
        return sourceLang
    }
    return if (targetLang.startsWith("en", ignoreCase = true)) "zh-CN" else "en"
}

private val QUICK_LANGUAGE_CODES = listOf(
    "auto", "en", "ja", "ko", "zh-CN", "zh-TW", "ms", "id", "th", "vi", "fr", "de", "es", "ru",
)

@Composable
internal fun QuickStartCard(
    settings: AppSettings?,
    serviceRunning: Boolean,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = settings ?: return
    val context = LocalContext.current
    val mode = quickModeOf(s.floatingButtonSkill, s.loopTriggerMode)
    val engine = quickEngineOf(s.translatorEngine)
    val offline = engine == QuickEngine.OFFLINE
    var modeMenuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Language pair: source -> target
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuickLanguageBubble(
                    code = s.sourceLang,
                    name = Languages.nameOf(context, s.sourceLang),
                    codes = QUICK_LANGUAGE_CODES.filter { code ->
                        !code.equals(s.targetLang, ignoreCase = true) && !(offline && code == "auto")
                    },
                    onPick = { picked -> onUpdate { applyQuickOcr(it.copy(sourceLang = picked)) } },
                )
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                QuickLanguageBubble(
                    code = s.targetLang,
                    name = Languages.nameOf(context, s.targetLang),
                    codes = QUICK_LANGUAGE_CODES.filter { code ->
                        code != "auto" && !code.equals(s.sourceLang, ignoreCase = true)
                    },
                    onPick = { picked -> onUpdate { it.copy(targetLang = picked) } },
                )
            }

            // Translator engine
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickEngine.entries.forEach { option ->
                        QuickEngineChip(
                            label = stringResource(option.labelRes),
                            selected = option == engine,
                            onClick = { onUpdate { applyQuickEngine(it, option) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (engine == QuickEngine.OFFLINE) {
                    Text(
                        text = stringResource(R.string.quick_engine_offline_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                if (engine == QuickEngine.LLM && quickLlmKeyMissing(s)) {
                    TextButton(onClick = onOpenSettings) {
                        Text(stringResource(R.string.quick_open_settings))
                    }
                }
            }

            // Mode dropdown + Start
            Row(
                modifier = Modifier.fillMaxWidth().height(60.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .clip(RoundedCornerShape(topStart = 30.dp, bottomStart = 30.dp))
                            .clickable { modeMenuOpen = true },
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            QuickModeBadge(mode)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(mode.labelRes),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        }
                    }
                    DropdownMenu(expanded = modeMenuOpen, onDismissRequest = { modeMenuOpen = false }) {
                        QuickMode.entries.forEach { option ->
                            DropdownMenuItem(
                                leadingIcon = { QuickModeBadge(option) },
                                text = {
                                    Text(
                                        stringResource(option.labelRes),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                },
                                onClick = {
                                    modeMenuOpen = false
                                    onUpdate { applyQuickMode(it, option) }
                                },
                            )
                        }
                    }
                }
                Button(
                    onClick = if (serviceRunning) {
                        onStop
                    } else {
                        {
                            onUpdate(::applyQuickOcr)
                            onStart()
                        }
                    },
                    modifier = Modifier.weight(1f).height(60.dp),
                    shape = RoundedCornerShape(topEnd = 30.dp, bottomEnd = 30.dp),
                    colors = if (serviceRunning) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    Text(
                        text = stringResource(
                            if (serviceRunning) R.string.quick_stop else R.string.quick_start
                        ),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickModeBadge(mode: QuickMode) {
    Box(
        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(mode.color),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = mode.badge, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
private fun QuickEngineChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QuickLanguageBubble(
    code: String,
    name: String,
    codes: List<String>,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { open = true },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = code.uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                codes.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(Languages.nameOf(context, option)) },
                        onClick = {
                            open = false
                            onPick(option)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = name,
            modifier = Modifier.width(110.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
