from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch(path: str, replacements: list[tuple[str, str]]) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    original = text
    for old, new in replacements:
        if old not in text:
            raise SystemExit(f"AudioMix patch anchor not found in {path}: {old[:120]!r}")
        text = text.replace(old, new, 1)
    p.write_text(text, encoding="utf-8")
    print(f"patched {path}: {len(original)} -> {len(text)} bytes")


patch("app/src/main/java/tv/own/owntv/player/PlayerHudDialogs.kt", [
    (
        "    audioDelayRemembered: Boolean = false,\n",
        "    audioDelayRemembered: Boolean = false,\n    // AudioMix uses 0.5-second sync steps; normal track sync keeps the existing 25 ms step.\n    audioDelayStepMs: Int = 25,\n",
    ),
    (
        "StepButton(stringResource(R.string.common_minus), enabled = (audioDelayMs ?: 0) > -5_000) { onAdjustAudioDelay(-AV_SYNC_STEP_MS) }",
        "StepButton(stringResource(R.string.common_minus), enabled = (audioDelayMs ?: 0) > -10_000) { onAdjustAudioDelay(-audioDelayStepMs) }",
    ),
    (
        "StepButton(stringResource(R.string.common_plus), enabled = (audioDelayMs ?: 0) < 5_000) { onAdjustAudioDelay(AV_SYNC_STEP_MS) }",
        "StepButton(stringResource(R.string.common_plus), enabled = (audioDelayMs ?: 0) < 10_000) { onAdjustAudioDelay(audioDelayStepMs) }",
    ),
])

patch("app/src/main/java/tv/own/owntv/player/PlayerHud.kt", [
    (
        "    onAudioMode: (() -> Unit)? = null,\n",
        "    onAudioMode: (() -> Unit)? = null,\n    // Live-only AudioMix: use the selected live channel as video and a second channel as audio.\n    audioMixEnabled: Boolean = false,\n    onAudioMix: (() -> Unit)? = null,\n",
    ),
    (
        "                    onMultiview = onMultiview, onRecordThis = onRecordThis, recordingThis = recordingThis, onBack = onBack,",
        "                    onMultiview = onMultiview, onRecordThis = onRecordThis, recordingThis = recordingThis,\n                    audioMixEnabled = audioMixEnabled, onAudioMix = onAudioMix, onBack = onBack,",
    ),
    (
        "                audioDelayRemembered = audioDelayRemembered,\n                onToggleRememberAudioDelay = if (player.audioDelayAvailable()) ({ player.toggleRememberAudioDelay() }) else null,",
        "                audioDelayRemembered = audioDelayRemembered,\n                audioDelayStepMs = if (audioMixEnabled) 500 else 25,\n                onToggleRememberAudioDelay = if (player.audioDelayAvailable()) ({ player.toggleRememberAudioDelay() }) else null,",
    ),
])

patch("app/src/main/java/tv/own/owntv/player/PlayerHudChrome.kt", [
    (
        "    onMultiview: (() -> Unit)? = null, onRecordThis: (() -> Unit)? = null, recordingThis: Boolean = false,\n    onBack: () -> Unit, modifier: Modifier = Modifier,",
        "    onMultiview: (() -> Unit)? = null, onRecordThis: (() -> Unit)? = null, recordingThis: Boolean = false,\n    audioMixEnabled: Boolean = false, onAudioMix: (() -> Unit)? = null,\n    onBack: () -> Unit, modifier: Modifier = Modifier,",
    ),
    (
        "            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {\n                // H2 — the ORDER comes from core's canonical list, not from the order these lines",
        "            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {\n                if (onAudioMix != null) {\n                    CtrlButton(OwnTVIcon.AUDIO, active = audioMixEnabled, label = stringResource(R.string.player_tool_audio)) { onAudioMix() }\n                }\n                // H2 — the ORDER comes from core's canonical list, not from the order these lines",
    ),
])

patch("app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt", [
    (
        "    var showHistoryList by remember { mutableStateOf(false) }\n",
        "    var showHistoryList by remember { mutableStateOf(false) }\n    // AudioMix source picker: selecting a channel keeps the current video channel playing and feeds\n    // only the selected channel's stream into the same mpv session as external audio.\n    var showAudioMixList by remember { mutableStateOf(false) }\n",
    ),
    (
        "    val historyChannels by produceState(emptyList<ChannelEntity>(), showHistoryList, previewChannel?.id) {",
        "    val audioMixEnabled by mpvEngine.audioMixEnabled.collectAsStateWithLifecycle()\n\n    val historyChannels by produceState(emptyList<ChannelEntity>(), showHistoryList, previewChannel?.id) {",
    ),
    (
        "                    inert = showChannelList || showHistoryList || showCategoryBrowser || showSubtitleSearch || showLocalSubPicker,",
        "                    inert = showChannelList || showHistoryList || showAudioMixList || showCategoryBrowser || showSubtitleSearch || showLocalSubPicker,",
    ),
    (
        "                    onAudioMode = toAudioMode,\n",
        "                    onAudioMode = toAudioMode,\n                    audioMixEnabled = isLiveChannel && !liveOnExo && audioMixEnabled,\n                    onAudioMix = if (isTunedLive && !liveOnExo && previewChannel != null) {\n                        { if (audioMixEnabled) mpvEngine.audioMixDisable() else showAudioMixList = true }\n                    } else null,\n",
    ),
    (
        "                if (showHistoryList && isLiveChannel && historyChannels.isNotEmpty()) {",
        "                if (showAudioMixList && isTunedLive && !liveOnExo) {\n                    tv.own.owntv.features.shell.components.ChannelListOverlay(\n                        channels = zapChannels.filter { it.id != previewChannel?.id },\n                        currentId = null,\n                        nowPlaying = emptyMap(),\n                        title = stringResource(R.string.player_tool_audio),\n                        providerNames = liveProviderNames,\n                        showNumbers = directTuneEnabled,\n                        alignEnd = true,\n                        onSelect = { channel ->\n                            showAudioMixList = false\n                            mpvEngine.audioMixEnable(channel.streamUrl, channel.httpHeaders)\n                        },\n                        onDismiss = { showAudioMixList = false },\n                        modifier = Modifier.fillMaxSize(),\n                    )\n                }\n                if (showHistoryList && isLiveChannel && historyChannels.isNotEmpty()) {",
    ),
])

print("AudioMix app patch applied")
