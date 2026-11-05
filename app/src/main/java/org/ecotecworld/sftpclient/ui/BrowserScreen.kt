package org.ecotecworld.sftpclient.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.ecotecworld.sftpclient.sftp.RemoteFile
import org.ecotecworld.sftpclient.sftp.RemotePaths
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var overflowMenu by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RemoteFile?>(null) }
    var deleteTarget by remember { mutableStateOf<RemoteFile?>(null) }

    LaunchedEffect(Unit) {
        vm.messages.collect { m ->
            val result = snackbar.showSnackbar(
                message = m.text,
                actionLabel = if (m.openUri != null) "Open" else null,
                withDismissAction = true,
                duration = if (m.openUri != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed && m.openUri != null) {
                openLocalFile(context, m.openUri, m.mimeType)
            }
        }
    }

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.requestUpload(uris)
    }

    BackHandler {
        if (vm.currentPath != "/") vm.goUp() else confirmDisconnect = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { confirmDisconnect = true }) {
                        Icon(Icons.Filled.Close, contentDescription = "Disconnect")
                    }
                },
                title = {
                    Column(Modifier.clickable { showGoTo = true }) {
                        Text(
                            vm.activeProfile?.displayName ?: "",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            vm.currentPath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.StartEllipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                    Box {
                        IconButton(onClick = { overflowMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("New folder") },
                                leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                                onClick = { overflowMenu = false; showNewFolder = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Home folder") },
                                leadingIcon = { Icon(Icons.Filled.Home, contentDescription = null) },
                                onClick = { overflowMenu = false; vm.goHome() },
                            )
                            DropdownMenuItem(
                                text = { Text("Go to path…") },
                                leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                                onClick = { overflowMenu = false; showGoTo = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Show hidden files") },
                                leadingIcon = { Checkbox(checked = vm.showHidden, onCheckedChange = null) },
                                onClick = { vm.showHidden = !vm.showHidden; overflowMenu = false },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { uploadPicker.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Filled.Upload, contentDescription = null) },
                text = { Text("Upload") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.padding(top = 4.dp))
            Box(Modifier.weight(1f)) {
                val list = vm.visibleFiles
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                    if (vm.currentPath != "/") {
                        item(key = "..") {
                            ListItem(
                                headlineContent = { Text("..") },
                                supportingContent = { Text("Parent folder") },
                                leadingContent = { Icon(Icons.Filled.ArrowUpward, contentDescription = null) },
                                modifier = Modifier.clickable { vm.goUp() },
                            )
                        }
                    }
                    items(list, key = { it.path }) { file ->
                        FileRow(
                            file = file,
                            onOpenFolder = { vm.openDirectory(file.path) },
                            onDownload = { vm.download(file) },
                            onRename = { renameTarget = file },
                            onDelete = { deleteTarget = file },
                        )
                    }
                }
                if (list.isEmpty() && !vm.loading) {
                    Text(
                        if (vm.files.isNotEmpty()) "Only hidden files here" else "This folder is empty",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            TransfersPanel(vm)
        }
    }

    if (showNewFolder) {
        TextInputDialog(
            title = "New folder", label = "Folder name", initial = "", confirmLabel = "Create",
            validate = { RemotePaths.isValidName(it.trim()) },
            onConfirm = { vm.createFolder(it); showNewFolder = false },
            onDismiss = { showNewFolder = false },
        )
    }
    if (showGoTo) {
        TextInputDialog(
            title = "Go to folder", label = "Path", initial = vm.currentPath, confirmLabel = "Open",
            validate = { it.isNotBlank() },
            onConfirm = { vm.openDirectory(it.trim()); showGoTo = false },
            onDismiss = { showGoTo = false },
        )
    }
    renameTarget?.let { f ->
        TextInputDialog(
            title = "Rename", label = "New name", initial = f.name, confirmLabel = "Rename",
            validate = { RemotePaths.isValidName(it.trim()) && it.trim() != f.name },
            onConfirm = { vm.rename(f, it); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { f ->
        ConfirmDialog(
            title = "Delete ${if (f.isDirectory && !f.isLink) "folder" else "file"}?",
            text = if (f.isDirectory && !f.isLink) "\"${f.name}\" and everything inside it will be permanently deleted from the server."
            else "\"${f.name}\" will be permanently deleted from the server.",
            confirmLabel = "Delete",
            onConfirm = { vm.delete(f); deleteTarget = null },
            onDismiss = { deleteTarget = null },
        )
    }
    if (confirmDisconnect) {
        val running = vm.transfers.count { it.status == TransferStatus.Running }
        ConfirmDialog(
            title = "Disconnect?",
            text = if (running > 0) "$running transfer(s) still running will be cancelled." else "You will return to your saved servers.",
            confirmLabel = "Disconnect",
            onConfirm = { confirmDisconnect = false; vm.disconnect() },
            onDismiss = { confirmDisconnect = false },
        )
    }
    vm.pendingUpload?.let { p ->
        val n = p.conflicts.size
        AlertDialog(
            onDismissRequest = { vm.resolvePendingUpload(null) },
            title = { Text(if (n == 1) "Replace file?" else "Replace $n files?") },
            text = {
                Text(
                    if (n == 1) "\"${p.conflicts[0]}\" already exists in this folder."
                    else "These already exist in this folder:\n" + p.conflicts.take(8).joinToString("\n") { "• $it" } +
                        if (n > 8) "\n…and ${n - 8} more" else "",
                )
            },
            confirmButton = { TextButton(onClick = { vm.resolvePendingUpload(true) }) { Text("Replace") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.resolvePendingUpload(null) }) { Text("Cancel") }
                    if (p.files.size > n) TextButton(onClick = { vm.resolvePendingUpload(false) }) { Text("Skip") }
                }
            },
        )
    }
}

@Composable
private fun FileRow(
    file: RemoteFile,
    onOpenFolder: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val date = remember(file.modifiedSeconds) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(file.modifiedSeconds * 1000))
    }
    ListItem(
        headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                if (file.isDirectory) date else "${Formatter.formatShortFileSize(context, file.size)} · $date",
                maxLines = 1,
            )
        },
        leadingContent = {
            Icon(
                when {
                    file.isDirectory -> Icons.Filled.Folder
                    file.isLink -> Icons.Filled.Link
                    else -> Icons.Filled.Description
                },
                contentDescription = if (file.isDirectory) "Folder" else "File",
                tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Options for ${file.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!file.isDirectory) {
                        DropdownMenuItem(
                            text = { Text("Download to phone") },
                            leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                            onClick = { menu = false; onDownload() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null) },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        },
        // Folders open on tap; files show their actions (so a tap never starts a big download by accident).
        modifier = Modifier.clickable { if (file.isDirectory) onOpenFolder() else menu = true },
    )
}

@Composable
private fun TransfersPanel(vm: MainViewModel) {
    if (vm.transfers.isEmpty()) return
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(true) }
    val active = vm.transfers.count { it.status == TransferStatus.Running }
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column {
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (active > 0) "Transfers · $active active" else "Transfers",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                )
                if (vm.transfers.size > active) TextButton(onClick = vm::clearFinishedTransfers) { Text("Clear finished") }
                Icon(if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, contentDescription = if (expanded) "Collapse" else "Expand")
            }
            if (expanded) {
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    items(vm.transfers, key = { it.id }) { t -> TransferRow(t, context, onCancel = { vm.cancelTransfer(t) }) }
                }
            }
        }
    }
}

@Composable
private fun TransferRow(t: Transfer, context: Context, onCancel: () -> Unit) {
    val sizeText = { b: Long -> Formatter.formatShortFileSize(context, b) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (t.isUpload) Icons.Filled.Upload else Icons.Filled.Download,
            contentDescription = if (t.isUpload) "Upload" else "Download",
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            when (t.status) {
                TransferStatus.Running -> {
                    if (t.total > 0) {
                        LinearProgressIndicator(
                            progress = { (t.done.toFloat() / t.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                        Text("${sizeText(t.done)} of ${sizeText(t.total)}", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                        Text(if (t.done > 0) sizeText(t.done) else "Starting…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                TransferStatus.Done -> Text(
                    if (t.isUpload) "Uploaded" else "Saved to Downloads",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                )
                TransferStatus.Failed -> Text(
                    "Failed" + (t.error?.let { ": $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                TransferStatus.Cancelled -> Text("Cancelled", style = MaterialTheme.typography.bodySmall)
            }
        }
        when {
            t.status == TransferStatus.Running ->
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel ${t.name}") }
            t.status == TransferStatus.Done && t.localUri != null ->
                IconButton(onClick = { openLocalFile(context, t.localUri!!, t.mimeType) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open ${t.name}")
                }
        }
    }
}

private fun openLocalFile(context: Context, uri: Uri, mimeType: String?) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app installed that can open this file", Toast.LENGTH_SHORT).show()
    }
}
