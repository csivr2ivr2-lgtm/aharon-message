package com.aharon.message.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.core.content.ContextCompat
import com.aharon.message.AppContainer
import com.aharon.message.acoustic.AcousticProfile
import com.aharon.message.model.ChatMessage
import com.aharon.message.model.Contact
import com.aharon.message.model.MessageStatus
import com.aharon.message.model.PendingPairing
import com.aharon.message.service.AcousticReceiverService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class MainTab { CHATS, PAIR, SETTINGS }

@Composable
fun AharonMessageRoot(container: AppContainer) {
    val context = LocalContext.current
    var microphoneGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        microphoneGranted = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (microphoneGranted && container.settings.receiverEnabled) {
            AcousticReceiverService.start(context)
        }
    }

    LaunchedEffect(microphoneGranted) {
        if (microphoneGranted && container.settings.receiverEnabled) {
            AcousticReceiverService.start(context)
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        if (!microphoneGranted) {
            PermissionScreen {
                val permissions = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                permissionLauncher.launch(permissions.toTypedArray())
            }
        } else {
            MainApp(container)
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(78.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.height(22.dp))
            Text("Aharon Message צריך גישה למיקרופון", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                "המיקרופון משמש רק לפענוח אותות אולטרסוניים בזמן שהמקלט פעיל. האודיו לא נשמר.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRequest) { Text("אשר והמשך") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainApp(container: AppContainer) {
    val contacts by container.engine.contacts.collectAsState()
    val messages by container.engine.messages.collectAsState()
    val pending by container.engine.pendingPairings.collectAsState()
    val running by AcousticReceiverService.running.collectAsState()

    var tab by remember { mutableStateOf(MainTab.CHATS) }
    var selectedContact by remember { mutableStateOf<Contact?>(null) }

    if (selectedContact != null) {
        ChatScreen(
            contact = selectedContact!!,
            messages = messages.filter { it.contactId == selectedContact!!.deviceId },
            onBack = { selectedContact = null },
            onSend = { container.engine.sendText(selectedContact!!, it) },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Aharon Message", fontWeight = FontWeight.Bold)
                        Text(
                            if (running) "מקלט אולטרסוני פעיל" else "המקלט כבוי",
                            fontSize = 12.sp,
                            color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    Icon(
                        if (running) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = null,
                        tint = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == MainTab.CHATS,
                    onClick = { tab = MainTab.CHATS },
                    icon = { Icon(Icons.Default.Chat, null) },
                    label = { Text("צ'אטים") },
                )
                NavigationBarItem(
                    selected = tab == MainTab.PAIR,
                    onClick = { tab = MainTab.PAIR },
                    icon = { Icon(Icons.Default.PersonAdd, null) },
                    label = { Text("צימוד") },
                )
                NavigationBarItem(
                    selected = tab == MainTab.SETTINGS,
                    onClick = { tab = MainTab.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("הגדרות") },
                )
            }
        }
    ) { padding ->
        when (tab) {
            MainTab.CHATS -> ChatsScreen(
                contacts = contacts,
                messages = messages,
                modifier = Modifier.padding(padding),
                onContact = { selectedContact = it },
                onOpenPairing = { tab = MainTab.PAIR },
            )
            MainTab.PAIR -> PairingScreen(
                container = container,
                pending = pending,
                modifier = Modifier.padding(padding),
            )
            MainTab.SETTINGS -> SettingsScreen(
                container = container,
                running = running,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun ChatsScreen(
    contacts: List<Contact>,
    messages: List<ChatMessage>,
    modifier: Modifier,
    onContact: (Contact) -> Unit,
    onOpenPairing: () -> Unit,
) {
    if (contacts.isEmpty()) {
        Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Chat, null, modifier = Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("עדיין אין אנשי קשר", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("צמד שני מכשירים קרובים כדי להתחיל לשלוח הודעות ללא רשת.")
                Spacer(Modifier.height(20.dp))
                Button(onClick = onOpenPairing) { Text("צימוד מכשיר") }
            }
        }
        return
    }

    LazyColumn(modifier.fillMaxSize()) {
        items(contacts, key = { it.deviceId }) { contact ->
            val last = messages.lastOrNull { it.contactId == contact.deviceId }
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onContact(contact) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(contact.name)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(contact.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        last?.body ?: "מוכן להודעות אולטרסוניות",
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (last != null) {
                    Text(formatTime(last.timestamp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Divider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    contact: Contact,
    messages: List<ChatMessage>,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val state = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) state.animateScrollToItem(messages.lastIndex)
    }
    val byteCount = draft.toByteArray(Charsets.UTF_8).size
    val sendEnabled = draft.isNotBlank() && byteCount <= 512

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(contact.name, 36)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(contact.name, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lock, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                Text("מוצפן מקצה לקצה", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().imePadding().padding(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("הודעה") },
                    supportingText = {
                        if (byteCount > 420) Text("$byteCount / 512 bytes")
                    },
                    isError = byteCount > 512,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (sendEnabled) {
                            onSend(draft)
                            draft = ""
                        }
                    }),
                    shape = RoundedCornerShape(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    enabled = sendEnabled,
                    onClick = {
                        onSend(draft)
                        draft = ""
                    },
                    modifier = Modifier.size(52.dp).clip(CircleShape).background(
                        if (sendEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                    ),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "שלח", tint = Color.White)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            items(messages, key = { it.id }) { message -> MessageBubble(message) }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.outgoing) Arrangement.Start else Arrangement.End,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (message.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(message.body)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(message.timestamp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (message.outgoing) {
                        Spacer(Modifier.width(5.dp))
                        when (message.status) {
                            MessageStatus.QUEUED -> Text("…", fontSize = 11.sp)
                            MessageStatus.SENT -> Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp))
                            MessageStatus.DELIVERED -> Icon(Icons.Default.DoneAll, null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                            MessageStatus.FAILED -> Text("!", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PairingScreen(container: AppContainer, pending: List<PendingPairing>, modifier: Modifier) {
    val context = LocalContext.current
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Sensors, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("צימוד אולטרסוני", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("שים את שני הטלפונים קרוב. באחד מהם לחץ על שידור בקשת צימוד. הצד השני ישיב אוטומטית.")
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = {
                        if (!AcousticReceiverService.running.value) AcousticReceiverService.start(context)
                        container.engine.startPairing()
                    }) {
                        Icon(Icons.Default.PersonAdd, null)
                        Spacer(Modifier.width(8.dp))
                        Text("שדר בקשת צימוד")
                    }
                }
            }
        }

        if (pending.isEmpty()) {
            item { Text("אין בקשות שממתינות לאישור.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(pending, key = { it.deviceId }) { pairing ->
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text(pairing.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Text("ודא שבשני הטלפונים מופיע אותו קוד:")
                        Text(
                            pairing.verificationCode,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                        Row {
                            Button(onClick = { container.engine.confirmPairing(pairing.deviceId) }) {
                                Icon(Icons.Default.Check, null)
                                Spacer(Modifier.width(5.dp))
                                Text("הקוד זהה")
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { container.engine.rejectPairing(pairing.deviceId) }) {
                                Icon(Icons.Default.Close, null)
                                Spacer(Modifier.width(5.dp))
                                Text("דחה")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(container: AppContainer, running: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val received by AcousticReceiverService.receivedFrames.collectAsState()
    val crcErrors by AcousticReceiverService.crcErrors.collectAsState()
    val signal by AcousticReceiverService.lastSignalDb.collectAsState()
    var displayName by remember { mutableStateOf(container.identity.displayName) }
    var profileId by remember { mutableStateOf(container.settings.acousticProfileId) }
    val micUltra = remember { AcousticProfile.microphoneSupport(context) }
    val speakerUltra = remember { AcousticProfile.speakerSupport(context) }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("זהות", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it.take(32) },
                label = { Text("שם שיופיע בצימוד") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = { container.identity.displayName = displayName }) { Text("שמור שם") }
            Text(
                "ID: ${container.identity.deviceUuid.toString().take(13)}…",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { Divider() }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("מקלט ברקע", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("נדרש כדי לקבל הודעות כשהמסך כבוי.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = running,
                    onCheckedChange = { enabled ->
                        container.settings.receiverEnabled = enabled
                        if (enabled) AcousticReceiverService.start(context) else AcousticReceiverService.stop(context)
                    }
                )
            }
        }

        item { Divider() }
        item {
            Text("תחום תדרים", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            ProfileChoice(
                profile = AcousticProfile.STRICT,
                selected = profileId == AcousticProfile.STRICT.id,
                description = "20.2–21.8 kHz · ברירת מחדל. מיועד להיות מחוץ לטווח השמיעה, אך תלוי בחומרה.",
            ) {
                profileId = AcousticProfile.STRICT.id
                container.settings.acousticProfileId = profileId
            }
            ProfileChoice(
                profile = AcousticProfile.COMPATIBLE,
                selected = profileId == AcousticProfile.COMPATIBLE.id,
                description = "18.8–20.4 kHz · תאימות גבוהה יותר. עלול להיות נשמע אצל אנשים צעירים.",
            ) {
                profileId = AcousticProfile.COMPATIBLE.id
                container.settings.acousticProfileId = profileId
            }
        }

        item { Divider() }
        item {
            Text("Diagnostics", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            DiagnosticRow("Near-ultrasound microphone", supportText(micUltra))
            DiagnosticRow("Near-ultrasound speaker", supportText(speakerUltra))
            DiagnosticRow("Receiver", if (running) "ACTIVE" else "OFF")
            DiagnosticRow("Frames received", received.toString())
            DiagnosticRow("CRC / decode errors", crcErrors.toString())
            DiagnosticRow("Last signal", signal?.let { String.format(Locale.US, "%.1f dBFS", it) } ?: "—")
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VolumeOff, null)
                    Spacer(Modifier.width(10.dp))
                    Text("Aharon Message אינו מקליט או שומר אודיו. הדגימות נבדקות בזיכרון ונזרקות לאחר הפענוח.")
                }
            }
        }
    }
}

@Composable
private fun ProfileChoice(
    profile: AcousticProfile,
    selected: Boolean,
    description: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(top = 10.dp)) {
            Text(profile.displayName, fontWeight = FontWeight.SemiBold)
            Text(description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Avatar(name: String, size: Int = 48) {
    Box(
        modifier = Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.trim().firstOrNull()?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.Bold)
    }
}

private fun supportText(value: Boolean?): String = when (value) {
    true -> "SUPPORTED"
    false -> "NOT DECLARED"
    null -> "UNKNOWN"
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
