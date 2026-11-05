package org.ecotecworld.sftpclient.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ecotecworld.sftpclient.data.AuthType
import org.ecotecworld.sftpclient.data.Profile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(vm: MainViewModel) {
    var connectTarget by remember { mutableStateOf<Profile?>(null) }
    var deleteTarget by remember { mutableStateOf<Profile?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("SFTP Client") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.editProfile(null) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New connection") },
            )
        },
    ) { padding ->
        if (vm.profiles.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.Dns, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("No saved servers yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Add a server you can reach over SSH to browse, upload and download its files.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(vm.profiles, key = { it.id }) { p ->
                    ProfileCard(
                        profile = p,
                        onConnect = { connectTarget = p },
                        onEdit = { vm.editProfile(p) },
                        onDelete = { deleteTarget = p },
                    )
                }
            }
        }
    }

    connectTarget?.let { p ->
        val isKey = p.authType == AuthType.KEY
        SecretDialog(
            title = "Connect to ${p.displayName}",
            label = if (isKey) "Key passphrase" else "Password for ${p.username}",
            optional = isKey,
            onConfirm = { secret -> connectTarget = null; vm.connect(p, secret) },
            onDismiss = { connectTarget = null },
        )
    }
    deleteTarget?.let { p ->
        ConfirmDialog(
            title = "Delete connection?",
            text = "\"${p.displayName}\" will be removed from this device. Files on the server are not affected.",
            confirmLabel = "Delete",
            onConfirm = { vm.deleteProfile(p); deleteTarget = null },
            onDismiss = { deleteTarget = null },
        )
    }
    if (vm.connecting && vm.hostKeyPrompt == null) {
        ProgressDialog("Connecting…", onCancel = vm::cancelConnect)
    }
    vm.connectError?.let { err ->
        AlertDialog(
            onDismissRequest = vm::dismissConnectError,
            title = { Text("Could not connect") },
            text = { Text(err) },
            confirmButton = { TextButton(onClick = vm::dismissConnectError) { Text("OK") } },
        )
    }
}

@Composable
private fun ProfileCard(profile: Profile, onConnect: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onConnect)) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            leadingContent = { Icon(if (profile.authType == AuthType.KEY) Icons.Filled.Key else Icons.Filled.Dns, contentDescription = null) },
            headlineContent = { Text(profile.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    "${profile.username}@${profile.host}" + if (profile.port != 22) ":${profile.port}" else "",
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = { menu = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(vm: MainViewModel, existing: Profile?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var host by rememberSaveable { mutableStateOf(existing?.host ?: "") }
    var port by rememberSaveable { mutableStateOf((existing?.port ?: 22).toString()) }
    var username by rememberSaveable { mutableStateOf(existing?.username ?: "") }
    var authType by rememberSaveable { mutableStateOf(existing?.authType ?: AuthType.PASSWORD) }
    var keyName by rememberSaveable { mutableStateOf(existing?.keyName) }
    var newKey by remember { mutableStateOf<ByteArray?>(null) }

    val portNum = port.toIntOrNull()
    val portValid = portNum != null && portNum in 1..65535
    val hasKey = newKey != null || (existing != null && existing.authType == AuthType.KEY && existing.keyName != null && keyName == existing.keyName)
    val valid = host.isNotBlank() && username.isNotBlank() && portValid && (authType == AuthType.PASSWORD || hasKey)

    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (true) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            buffer.write(chunk, 0, n)
                            require(buffer.size() <= MAX_KEY_SIZE) { "File is too large to be a private key." }
                        }
                        val bytes = buffer.toByteArray()
                        val text = String(bytes, Charsets.UTF_8)
                        require(text.contains("PRIVATE KEY") || text.contains("PuTTY-User-Key-File")) {
                            "This doesn't look like a private key file."
                        }
                        bytes
                    }
                }
            }
            result.onSuccess { bytes ->
                newKey = bytes
                keyName = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "private key"
            }.onFailure { e ->
                Toast.makeText(context, e.message ?: "Could not read the key file", Toast.LENGTH_LONG).show()
            }
        }
    }

    BackHandler { vm.closeEditor() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New connection" else "Edit connection") },
                navigationIcon = {
                    IconButton(onClick = vm::closeEditor) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(
                        enabled = valid,
                        onClick = {
                            vm.saveProfile(
                                Profile(
                                    id = existing?.id ?: "",
                                    name = name.trim(),
                                    host = host.trim(),
                                    port = portNum ?: 22,
                                    username = username.trim(),
                                    authType = authType,
                                    keyName = if (authType == AuthType.KEY) keyName else null,
                                ),
                                newKey = if (authType == AuthType.KEY) newKey else null,
                            )
                        },
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name (optional)") }, placeholder = { Text("My server") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host, onValueChange = { host = it.trim() },
                    label = { Text("Host") }, placeholder = { Text("example.com or 192.168.1.10") },
                    singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") }, singleLine = true, isError = !portValid,
                    modifier = Modifier.width(96.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                )
            }
            OutlinedTextField(
                value = username, onValueChange = { username = it.trim() },
                label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            )

            Text("Sign in with", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Column(Modifier.selectableGroup()) {
                AuthOption("Password", "Asked for each time you connect", authType == AuthType.PASSWORD) { authType = AuthType.PASSWORD }
                AuthOption("Private key", "OpenSSH, PEM or PuTTY key file", authType == AuthType.KEY) { authType = AuthType.KEY }
            }
            if (authType == AuthType.KEY) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { keyPicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.Key, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (hasKey) "Replace key" else "Import key file")
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (hasKey) keyName ?: "Key imported" else "No key imported",
                        color = if (hasKey) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "The key is copied into this app's private storage. Its passphrase is never saved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AuthOption(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val MAX_KEY_SIZE = 64 * 1024
