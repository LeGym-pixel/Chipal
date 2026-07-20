@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.svpn.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.svpn.app.data.ButtonDesign
import com.svpn.app.data.PeladuLinkParser
import com.svpn.app.data.Protocol
import com.svpn.app.data.XrayLinkParser
import com.svpn.app.data.ServerProfile
import com.svpn.app.data.SvpnRepository
import com.svpn.app.data.WgConfigParser
import com.svpn.app.ui.components.HexButton
import com.svpn.app.ui.components.HexConnectButton
import com.svpn.app.ui.components.HexInputField
import com.svpn.app.ui.components.OuroborosPhase
import com.svpn.app.ui.components.OuroborosRing
import com.svpn.app.ui.components.ChainsRing
import com.svpn.app.ui.components.SunRing
import com.svpn.app.ui.components.DoubleSunRing
import com.svpn.app.ui.components.SwordStab
import com.svpn.app.ui.theme.PeladuGreen
import com.svpn.app.ui.theme.PeladuTheme
import com.svpn.app.ui.theme.PeladuWhite
import com.svpn.app.util.LocaleHelper
import com.svpn.app.util.PeladuNotifier
import com.svpn.app.vpn.TunnelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Screen { HOME, ADD_SERVER, SETTINGS, TUNNEL_SETTINGS, SERVER_LIST }

/**
 * Builds a diagnostic error message instead of a generic fallback — the
 * underlying backend library sometimes throws exceptions with a null
 * message, and just showing "Failed to connect" in that case hides
 * everything useful for figuring out what actually went wrong.
 */
private fun describeConnectError(e: Throwable, context: android.content.Context): String {
    val parts = mutableListOf<String>()
    var current: Throwable? = e
    var depth = 0
    while (current != null && depth < 4) {
        val className = current.javaClass.simpleName
        val msg = current.message
        parts.add(if (msg.isNullOrBlank()) className else "$className: $msg")
        current = current.cause
        depth++
    }
    return if (parts.isEmpty()) {
        context.getString(R.string.error_connect_failed)
    } else {
        parts.joinToString("\n← caused by: ")
    }
}

/**
 * A simple, honest latency number: how long it takes to open a TCP
 * connection to a reliable, always-up host (Cloudflare) *through* the
 * active tunnel. This measures "how fast does the internet feel right
 * now", not literally the ping to the VPN server itself (that's UDP and
 * doesn't have a clean round-trip primitive available without native
 * ICMP access, which Android doesn't grant apps without root).
 */
private fun measureLatencyMs(): Int? {
    return try {
        val start = System.currentTimeMillis()
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("1.1.1.1", 443), 3000)
        }
        (System.currentTimeMillis() - start).toInt()
    } catch (_: Exception) {
        null
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var repository: SvpnRepository
    private lateinit var tunnelManager: TunnelManager

    private val incomingLink = mutableStateOf<String?>(null)
    private var pendingVpnGrantAction: (() -> Unit)? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingVpnGrantAction?.invoke()
        }
        pendingVpnGrantAction = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* if denied, PeladuNotifier just silently skips showing the status notification */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = (application as SvpnApplication).repository
        tunnelManager = TunnelManager(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        incomingLink.value = intent?.dataString

        setContent {
            PeladuTheme {
                SvpnApp(
                    repository = repository,
                    tunnelManager = tunnelManager,
                    incomingLink = incomingLink,
                    requestVpnPermission = { onGranted ->
                        val vpnIntent = VpnService.prepare(this)
                        if (vpnIntent != null) {
                            pendingVpnGrantAction = onGranted
                            vpnPermissionLauncher.launch(vpnIntent)
                        } else {
                            onGranted()
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingLink.value = intent.dataString
    }
}

@Composable
private fun PeladuLogo(heightDp: androidx.compose.ui.unit.Dp = 90.dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(id = R.drawable.peladu_logo),
        contentDescription = "Peladu",
        modifier = modifier.height(heightDp)
    )
}

/** Hexagon back button, matching the one used on Add Server, placed at the bottom of every screen. */
@Composable
private fun HexBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    HexButton(modifier = modifier, sizeDp = 120.dp, onClick = onClick) {
        Text(stringResource(R.string.hex_back), color = PeladuWhite, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SvpnApp(
    repository: SvpnRepository,
    tunnelManager: TunnelManager,
    incomingLink: MutableState<String?>,
    requestVpnPermission: (onGranted: () -> Unit) -> Unit
) {
    // Simple back-stack: HOME is always the root. Pushing/popping drives both
    // in-app navigation buttons and the system Back gesture/button.
    val backStack = remember { mutableStateListOf(Screen.HOME) }
    val screen = backStack.last()
    fun navigateTo(target: Screen) { backStack.add(target) }
    fun goBack() { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

    BackHandler(enabled = backStack.size > 1) { goBack() }

    val context = LocalContext.current
    val servers by repository.servers.collectAsState(initial = emptyList())
    val activeId by repository.activeServerId.collectAsState(initial = null)
    val buttonDesign by repository.buttonDesign.collectAsState(initial = ButtonDesign.CHAINS)
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(OuroborosPhase.STOPPED) }
    var editingServer by remember { mutableStateOf<ServerProfile?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var prefillLinkText by remember { mutableStateOf<String?>(null) }
    var statusSubLabel by remember { mutableStateOf<String?>(null) }
    var latencyMs by remember { mutableStateOf<Int?>(null) }

    // Any deep link (peladu:// or vpn://) that arrives while the app is
    // running jumps straight to the Add Server screen with the raw link
    // text ready to import.
    LaunchedEffect(incomingLink.value) {
        val link = incomingLink.value
        if (link != null && PeladuLinkParser.looksLikeLink(link)) {
            prefillLinkText = link
            navigateTo(Screen.ADD_SERVER)
            incomingLink.value = null
        }
    }

    Scaffold(containerColor = Color.Black) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    phase = phase,
                    buttonDesign = buttonDesign,
                    activeServer = servers.find { it.id == activeId },
                    statusSubLabel = statusSubLabel,
                    latencyMs = latencyMs,
                    onAdd = { navigateTo(Screen.ADD_SERVER) },
                    onSettings = { navigateTo(Screen.SETTINGS) },
                    onServerList = { navigateTo(Screen.SERVER_LIST) },
                    onConnectToggle = {
                        val active = servers.find { it.id == activeId }
                        when (phase) {
                            OuroborosPhase.STOPPED -> {
                                if (active == null) {
                                    errorMessage = context.getString(R.string.error_no_server_in_list)
                                    return@HomeScreen
                                }
                                requestVpnPermission {
                                    phase = OuroborosPhase.CONNECTING
                                    latencyMs = null
                                    statusSubLabel = context.getString(R.string.status_connecting)
                                    scope.launch {
                                        // Switches the status text to "ещё немного..." after a
                                        // second, purely cosmetic — makes the ~2s minimum feel
                                        // like an actual multi-step connection instead of a
                                        // suspiciously exact fixed pause.
                                        val subLabelJob = launch {
                                            delay(500)
                                            statusSubLabel = context.getString(R.string.status_almost)
                                        }
                                        try {
                                            val start = System.currentTimeMillis()
                                            withContext(Dispatchers.IO) { tunnelManager.connect(active) }
                                            val elapsed = System.currentTimeMillis() - start
                                            val minTotalMs = 1000L
                                            if (elapsed < minTotalMs) delay(minTotalMs - elapsed)
                                            subLabelJob.cancel()
                                            phase = OuroborosPhase.CONNECTED
                                            PeladuNotifier.showConnected(context)
                                            latencyMs = withContext(Dispatchers.IO) { measureLatencyMs() }
                                        } catch (e: Exception) {
                                            subLabelJob.cancel()
                                            phase = OuroborosPhase.STOPPED
                                            errorMessage = describeConnectError(e, context)
                                        }
                                    }
                                }
                            }
                            OuroborosPhase.CONNECTED -> {
                                phase = OuroborosPhase.DISCONNECTING
                                latencyMs = null
                                PeladuNotifier.clear(context)
                                scope.launch {
                                    withContext(Dispatchers.IO) { tunnelManager.disconnect() }
                                }
                            }
                            else -> { /* ignore taps mid-transition */ }
                        }
                    },
                    onReturnedToRest = { phase = OuroborosPhase.STOPPED }
                )
                Screen.SERVER_LIST -> ServerListScreen(
                    servers = servers,
                    activeId = activeId,
                    onOpenServer = { editingServer = it; navigateTo(Screen.TUNNEL_SETTINGS) },
                    onBack = { goBack() }
                )
                Screen.ADD_SERVER -> AddServerScreen(
                    initialText = prefillLinkText,
                    onSave = { profile, showUnofficialWarning ->
                        scope.launch {
                            repository.addServer(profile)
                            repository.setActiveServer(profile.id)
                            prefillLinkText = null
                            goBack()
                            if (showUnofficialWarning) {
                                infoMessage = context.getString(R.string.warning_unofficial_config)
                            }
                        }
                    },
                    onError = { errorMessage = it },
                    onCancel = { prefillLinkText = null; goBack() }
                )
                Screen.SETTINGS -> SettingsScreen(
                    repository = repository,
                    onBack = { goBack() }
                )
                Screen.TUNNEL_SETTINGS -> editingServer?.let { server ->
                    TunnelSettingsScreen(
                        server = server,
                        isActive = server.id == activeId,
                        onSave = { updated ->
                            scope.launch {
                                repository.updateServer(updated)
                                goBack()
                            }
                        },
                        onSelectServer = {
                            val switchingServer = server.id != activeId
                            if (switchingServer && phase != OuroborosPhase.STOPPED) {
                                // Avoid leaving a stale tunnel running for the old
                                // server once a different one becomes "active" —
                                // tear it down first instead of leaving the app in
                                // a confusing half-connected state.
                                PeladuNotifier.clear(context)
                                phase = OuroborosPhase.STOPPED
                                latencyMs = null
                                scope.launch {
                                    withContext(Dispatchers.IO) { tunnelManager.disconnect() }
                                    repository.setActiveServer(server.id)
                                }
                            } else {
                                scope.launch { repository.setActiveServer(server.id) }
                            }
                        },
                        onDelete = {
                            scope.launch {
                                repository.removeServer(server.id)
                                goBack()
                            }
                        },
                        onCancel = { goBack() }
                    )
                }
            }

            errorMessage?.let { msg ->
                AlertDialog(
                    onDismissRequest = { errorMessage = null },
                    confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("OK") } },
                    title = { Text(stringResource(R.string.error_title)) },
                    text = { Text(msg) }
                )
            }
            infoMessage?.let { msg ->
                AlertDialog(
                    onDismissRequest = { infoMessage = null },
                    confirmButton = { TextButton(onClick = { infoMessage = null }) { Text("OK") } },
                    title = { Text("PeladuVPN") },
                    text = { Text(msg) }
                )
            }
        }
    }
}

/** The honeycomb quick-menu: add server (+), settings, news, server list. */
@Composable
private fun HoneycombMenu(
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onServerList: () -> Unit
) {
    val hex = 108.dp
    Box(modifier = Modifier.size(hex * 2.3f, hex * 1.9f)) {
        HexButton(
            modifier = Modifier.align(Alignment.TopCenter),
            sizeDp = hex,
            onClick = onAdd
        ) {
            Text("+", color = PeladuWhite, fontSize = 30.sp)
        }
        HexButton(
            modifier = Modifier.align(Alignment.TopCenter).offset(x = -hex * 0.53f, y = hex * 0.8f),
            sizeDp = hex,
            onClick = onSettings
        ) {
            Text(stringResource(R.string.hex_settings), color = PeladuWhite, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        HexButton(
            modifier = Modifier.align(Alignment.TopCenter).offset(x = hex * 0.53f, y = hex * 0.8f),
            sizeDp = hex,
            onClick = onServerList
        ) {
            Text(stringResource(R.string.hex_server_list), color = PeladuWhite, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun HomeScreen(
    phase: OuroborosPhase,
    buttonDesign: ButtonDesign,
    activeServer: ServerProfile?,
    statusSubLabel: String?,
    latencyMs: Int?,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onServerList: () -> Unit,
    onConnectToggle: () -> Unit,
    onReturnedToRest: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PeladuLogo()
            Spacer(Modifier.height(24.dp))
            HoneycombMenu(onAdd = onAdd, onSettings = onSettings, onServerList = onServerList)
            Spacer(Modifier.height(24.dp))

            if (activeServer != null) {
                Text(
                    activeServer.name,
                    color = PeladuWhite,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onServerList)
                )
                Spacer(Modifier.height(6.dp))
                val statusText = when (phase) {
                    OuroborosPhase.STOPPED -> stringResource(R.string.status_disconnected)
                    OuroborosPhase.CONNECTING -> statusSubLabel ?: stringResource(R.string.status_connecting)
                    OuroborosPhase.CONNECTED -> stringResource(R.string.status_connected)
                    OuroborosPhase.DISCONNECTING -> stringResource(R.string.status_disconnected)
                }
                Text(
                    statusText,
                    color = if (phase == OuroborosPhase.CONNECTED) PeladuGreen else PeladuWhite.copy(alpha = 0.75f),
                    fontSize = 18.sp
                )
                if (phase == OuroborosPhase.CONNECTED && latencyMs != null) {
                    Spacer(Modifier.height(2.dp))
                    Text("${latencyMs}ms", color = PeladuWhite.copy(alpha = 0.6f), fontSize = 14.sp)
                }
            }

            // Reserve space for the button-design overlay below.
            Spacer(Modifier.height(220.dp))
        }

        Box(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            // Drawn first so it sits on the layer below the hex button.
            when (buttonDesign) {
                ButtonDesign.OUROBOROS -> OuroborosRing(
                    phase = phase,
                    onReturnedToRest = onReturnedToRest,
                    diameter = 260.dp
                )
                ButtonDesign.SWORD -> SwordStab(
                    phase = phase,
                    onReturnedToRest = onReturnedToRest,
                    diameter = 260.dp
                )
                ButtonDesign.CHAINS -> ChainsRing(
                    phase = phase,
                    onReturnedToRest = onReturnedToRest,
                    diameter = 260.dp
                )
                ButtonDesign.SUN -> SunRing(
                    phase = phase,
                    onReturnedToRest = onReturnedToRest,
                    diameter = 260.dp
                )
                ButtonDesign.DOUBLE_SUN -> DoubleSunRing(
                    phase = phase,
                    onReturnedToRest = onReturnedToRest,
                    diameter = 260.dp
                )
            }
            HexConnectButton(
                sizeDp = 150.dp,
                connected = phase == OuroborosPhase.CONNECTED,
                onClick = onConnectToggle
            ) {
                // Intentionally blank — the ring/border state alone communicates
                // status, matching the reference design.
            }
        }
    }
}

@Composable
private fun ServerListScreen(
    servers: List<ServerProfile>,
    activeId: String?,
    onOpenServer: (ServerProfile) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PeladuLogo(heightDp = 56.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.servers_header),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White
        )
        Spacer(Modifier.height(16.dp))

        if (servers.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.no_servers),
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        } else {
            servers.chunked(2).forEach { rowServers ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    rowServers.forEach { server ->
                        val isActive = server.id == activeId
                        HexButton(
                            sizeDp = 130.dp,
                            borderColor = if (isActive) PeladuGreen else PeladuWhite,
                            borderWidth = if (isActive) 4f else 2f,
                            onClick = { onOpenServer(server) }
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (server.isPeladuOfficial) {
                                    Image(
                                        painter = painterResource(id = R.drawable.peladu_logo),
                                        contentDescription = null,
                                        modifier = Modifier.height(16.dp).padding(bottom = 4.dp)
                                    )
                                }
                                Text(
                                    server.name,
                                    color = if (isActive) PeladuGreen else PeladuWhite,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                                Text(
                                    server.protocol.displayName,
                                    color = if (isActive) PeladuGreen.copy(alpha = 0.85f) else PeladuWhite.copy(alpha = 0.7f),
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        HexBackButton(onClick = onBack)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AddServerScreen(
    initialText: String?,
    onSave: (ServerProfile, showUnofficialWarning: Boolean) -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit
) {
    var configText by remember { mutableStateOf(initialText ?: "") }
    // Filled in when the person imports a file — used as the server name
    // fallback (after any name embedded in the config text itself), so a
    // server exported as "frankfurt.conf" keeps being called "frankfurt".
    var pickedFileName by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                configText = stream.bufferedReader().readText()
            }
            pickedFileName = queryDisplayName(context, uri)?.removeSuffix(".conf")?.removeSuffix(".txt")
        } catch (e: Exception) {
            onError(context.getString(R.string.error_read_file, e.message ?: ""))
        }
    }

    fun trySave() {
        val fallbackName = pickedFileName?.takeIf { it.isNotBlank() }
            ?: "Server ${System.currentTimeMillis() % 1000}"
        val trimmed = configText.trim()
        try {
            when {
                XrayLinkParser.detect(trimmed) != null -> {
                    val profile = XrayLinkParser.parse(trimmed, fallbackName)
                    onSave(profile, false)
                }
                PeladuLinkParser.looksLikeLink(trimmed) -> {
                    val result = PeladuLinkParser.parse(trimmed, fallbackName)
                    onSave(result.profile, !result.isOfficial)
                }
                else -> {
                    val profile = WgConfigParser.parse(trimmed, fallbackName)
                    onSave(profile, false)
                }
            }
        } catch (e: XrayLinkParser.ParseException) {
            onError(e.message ?: context.getString(R.string.error_link_parse))
        } catch (e: PeladuLinkParser.LinkParseException) {
            onError(e.message ?: context.getString(R.string.error_link_parse))
        } catch (e: WgConfigParser.ParseException) {
            onError(e.message ?: context.getString(R.string.error_config_parse_generic))
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PeladuLogo(heightDp = 70.dp)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.enter_key_title), color = PeladuWhite, fontSize = 22.sp)
        Spacer(Modifier.height(20.dp))

        // The field figures out on its own whether it's a plain .conf, a
        // vpn:// link, or a peladu://:peladu: link — no format hint shown.
        HexInputField(
            value = configText,
            onValueChange = { configText = it },
            placeholder = stringResource(R.string.placeholder_paste_key)
        )

        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.import_file_link),
            color = PeladuWhite.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier.clickable { filePicker.launch("*/*") }.padding(8.dp)
        )

        Spacer(Modifier.height(20.dp))
        HexButton(sizeDp = 120.dp, borderColor = PeladuGreen, onClick = { trySave() }) {
            Text(stringResource(R.string.hex_save), color = PeladuGreen, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(12.dp))
        HexBackButton(onClick = onCancel)
        Spacer(Modifier.height(24.dp))
    }
}

/** Resolves a content:// Uri's display name (e.g. "frankfurt.conf") without needing storage permissions. */
private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }
}

/** One slot in a 5-hex honeycomb (3 on top, 2 nested below). */
private data class HoneycombSlot(
    val selected: Boolean,
    val onClick: () -> Unit,
    val content: @Composable () -> Unit
)

/**
 * Arranges exactly 5 hexagons: 3 across the top touching edge-to-edge, and
 * 2 nested in the valleys underneath — matches the reference layout for
 * both the language picker and the button-design picker.
 */
@Composable
private fun HoneycombFive(hexSize: androidx.compose.ui.unit.Dp, slots: List<HoneycombSlot>) {
    require(slots.size == 5)
    val density = LocalDensity.current
    val horizSpacingPx = with(density) { (hexSize * 0.866f).toPx() }
    val vertSpacingPx = with(density) { (hexSize * 0.75f).toPx() }
    val horizSpacingDp = with(density) { horizSpacingPx.toDp() }
    val vertSpacingDp = with(density) { vertSpacingPx.toDp() }

    Box(modifier = Modifier.size(horizSpacingDp * 3f, hexSize + vertSpacingDp)) {
        val topXs = listOf(-horizSpacingDp, 0.dp, horizSpacingDp)
        topXs.forEachIndexed { i, x ->
            val slot = slots[i]
            HexButton(
                modifier = Modifier.align(Alignment.TopCenter).offset(x = x, y = 0.dp),
                sizeDp = hexSize,
                borderColor = if (slot.selected) PeladuGreen else PeladuWhite,
                borderWidth = if (slot.selected) 4f else 2f,
                onClick = slot.onClick
            ) { slot.content() }
        }
        val bottomXs = listOf(-horizSpacingDp / 2f, horizSpacingDp / 2f)
        bottomXs.forEachIndexed { i, x ->
            val slot = slots[3 + i]
            HexButton(
                modifier = Modifier.align(Alignment.TopCenter).offset(x = x, y = vertSpacingDp),
                sizeDp = hexSize,
                borderColor = if (slot.selected) PeladuGreen else PeladuWhite,
                borderWidth = if (slot.selected) 4f else 2f,
                onClick = slot.onClick
            ) { slot.content() }
        }
    }
}

@Composable
private fun SettingsScreen(repository: SvpnRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as? Activity
    // Drives the visual selection directly from the locale that's actually
    // applied right now (synchronous, via AppCompatDelegate) instead of the
    // DataStore-persisted value — the DataStore write is async and can lose
    // a race against activity.recreate(), leaving the radio button on the
    // previous language even though the UI itself switched correctly.
    var currentLang by remember { mutableStateOf(LocaleHelper.current()) }
    val buttonDesign by repository.buttonDesign.collectAsState(initial = ButtonDesign.CHAINS)

    fun selectLanguage(code: String) {
        currentLang = code
        LocaleHelper.apply(code)
        scope.launch { repository.setLanguage(code) }
        // Belt-and-suspenders: force an immediate recreate so the new
        // language shows right away, regardless of any OEM quirks in
        // AppCompat's automatic recreate-on-locale-change.
        activity?.recreate()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth()) {
            PeladuLogo(heightDp = 56.dp)
        }
        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
        Spacer(Modifier.height(12.dp))

        HoneycombFive(
            hexSize = 108.dp,
            slots = listOf(
                HoneycombSlot(currentLang == "ru", { selectLanguage("ru") }) {
                    Text("русский", color = if (currentLang == "ru") PeladuGreen else PeladuWhite, fontSize = 13.sp, textAlign = TextAlign.Center)
                },
                HoneycombSlot(currentLang == "tk", { selectLanguage("tk") }) {
                    Text("türkmen dili", color = if (currentLang == "tk") PeladuGreen else PeladuWhite, fontSize = 12.sp, textAlign = TextAlign.Center)
                },
                HoneycombSlot(currentLang == "ky", { selectLanguage("ky") }) {
                    Text("кыргызча", color = if (currentLang == "ky") PeladuGreen else PeladuWhite, fontSize = 13.sp, textAlign = TextAlign.Center)
                },
                HoneycombSlot(currentLang == "kk", { selectLanguage("kk") }) {
                    Text("қазақ тілі", color = if (currentLang == "kk") PeladuGreen else PeladuWhite, fontSize = 13.sp, textAlign = TextAlign.Center)
                },
                HoneycombSlot(currentLang == "en", { selectLanguage("en") }) {
                    Text("English", color = if (currentLang == "en") PeladuGreen else PeladuWhite, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            )
        )

        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_button_design), style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
        Spacer(Modifier.height(12.dp))

        HoneycombFive(
            hexSize = 108.dp,
            slots = listOf(
                HoneycombSlot(
                    buttonDesign == ButtonDesign.OUROBOROS,
                    { scope.launch { repository.setButtonDesign(ButtonDesign.OUROBOROS) } }
                ) { Image(painterResource(id = R.drawable.ouroboros), null, modifier = Modifier.size(76.dp)) },
                HoneycombSlot(
                    buttonDesign == ButtonDesign.CHAINS,
                    { scope.launch { repository.setButtonDesign(ButtonDesign.CHAINS) } }
                ) { Image(painterResource(id = R.drawable.chains), null, modifier = Modifier.size(76.dp)) },
                HoneycombSlot(
                    buttonDesign == ButtonDesign.SUN,
                    { scope.launch { repository.setButtonDesign(ButtonDesign.SUN) } }
                ) { Image(painterResource(id = R.drawable.sun), null, modifier = Modifier.size(76.dp)) },
                HoneycombSlot(
                    buttonDesign == ButtonDesign.SWORD,
                    { scope.launch { repository.setButtonDesign(ButtonDesign.SWORD) } }
                ) { Image(painterResource(id = R.drawable.sword), null, modifier = Modifier.size(76.dp)) },
                HoneycombSlot(
                    buttonDesign == ButtonDesign.DOUBLE_SUN,
                    { scope.launch { repository.setButtonDesign(ButtonDesign.DOUBLE_SUN) } }
                ) { Image(painterResource(id = R.drawable.double_sun), null, modifier = Modifier.size(76.dp)) }
            )
        )

        Spacer(Modifier.height(32.dp))
        HexBackButton(onClick = onBack)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun TunnelSettingsScreen(
    server: ServerProfile,
    isActive: Boolean,
    onSave: (ServerProfile) -> Unit,
    onSelectServer: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit
) {
    var mtu by remember { mutableStateOf(server.mtu) }
    var dns by remember { mutableStateOf(server.dns) }
    var allowedIps by remember { mutableStateOf(server.allowedIps) }
    var keepalive by remember { mutableStateOf(server.persistentKeepalive) }
    // Shown/edited as plain "Key = value" lines, one per field — works for
    // Jc/Jmin/Jmax/S1-S4/H1-H4/I1-I5 and whatever else a server might need,
    // without hardcoding a fixed list of fields in the UI.
    var extraFieldsText by remember {
        mutableStateOf(server.extraInterfaceFields.entries.joinToString("\n") { (k, v) -> "$k = $v" })
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PeladuLogo(heightDp = 56.dp)
        Spacer(Modifier.height(12.dp))
        Text(server.name, style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            if (isActive) "✓ " + stringResource(R.string.btn_select_server) else server.endpoint,
            color = if (isActive) PeladuGreen else Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp
        )
        Spacer(Modifier.height(16.dp))

        HexButton(
            sizeDp = 130.dp,
            borderColor = if (isActive) PeladuGreen else PeladuWhite,
            onClick = onSelectServer
        ) {
            Text(
                stringResource(R.string.btn_select_server),
                color = if (isActive) PeladuGreen else PeladuWhite,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.tunnel_section_basic), style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
        LabeledField(stringResource(R.string.field_mtu), mtu) { mtu = it }
        LabeledField(stringResource(R.string.field_dns), dns) { dns = it }
        LabeledField(stringResource(R.string.field_allowed_ips), allowedIps) { allowedIps = it }
        LabeledField(stringResource(R.string.field_keepalive), keepalive) { keepalive = it }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.tunnel_section_obfuscation), style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = extraFieldsText,
            onValueChange = { extraFieldsText = it },
            modifier = Modifier.fillMaxWidth().height(220.dp).padding(vertical = 4.dp)
        )

        Spacer(Modifier.height(20.dp))
        HexButton(sizeDp = 120.dp, borderColor = PeladuGreen, onClick = {
            val extras = LinkedHashMap<String, String>()
            extraFieldsText.lines().forEach { line ->
                val idx = line.indexOf('=')
                if (idx > 0) {
                    val k = line.substring(0, idx).trim()
                    val v = line.substring(idx + 1).trim()
                    if (k.isNotEmpty()) extras[k] = v
                }
            }
            onSave(
                server.copy(
                    mtu = mtu, dns = dns, allowedIps = allowedIps,
                    persistentKeepalive = keepalive,
                    extraInterfaceFields = extras
                )
            )
        }) {
            Text(stringResource(R.string.hex_save), color = PeladuGreen, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(12.dp))
        HexBackButton(onClick = onCancel)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.cd_delete),
            color = Color(0xFFFF6B6B),
            fontSize = 14.sp,
            modifier = Modifier.clickable(onClick = onDelete).padding(8.dp)
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun LabeledField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}
