package org.ecotecworld.sftpclient.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.SftpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.ecotecworld.sftpclient.data.AuthType
import org.ecotecworld.sftpclient.data.DeviceFiles
import org.ecotecworld.sftpclient.data.Profile
import org.ecotecworld.sftpclient.data.ProfileStore
import org.ecotecworld.sftpclient.sftp.ConnectParams
import org.ecotecworld.sftpclient.sftp.RemoteFile
import org.ecotecworld.sftpclient.sftp.RemotePaths
import org.ecotecworld.sftpclient.sftp.SftpConnection
import java.io.File
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID

sealed interface Screen {
    data object Profiles : Screen
    data class Editor(val profile: Profile?) : Screen
    data object Browser : Screen
}

class HostKeyPrompt(val message: String, val answer: CompletableDeferred<Boolean>)

class PendingUpload(val files: List<DeviceFiles.PickedFile>, val conflicts: List<String>, val targetDir: String)

class UiMessage(val text: String, val openUri: Uri? = null, val mimeType: String? = null)

enum class TransferStatus { Running, Done, Failed, Cancelled }

class Transfer(val id: Long, val name: String, val isUpload: Boolean, val mimeType: String) {
    var done by mutableLongStateOf(0L)
    var total by mutableLongStateOf(-1L)
    var status by mutableStateOf(TransferStatus.Running)
    var error by mutableStateOf<String?>(null)
    var localUri by mutableStateOf<Uri?>(null)
    internal var job: Job? = null
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProfileStore(app)
    private val knownHosts = File(app.filesDir, "known_hosts")
    private val transferSlots = Semaphore(3)
    private var nextTransferId = 1L

    // ---- Connection / navigation state ----
    var screen by mutableStateOf<Screen>(Screen.Profiles); private set
    var profiles by mutableStateOf(store.load()); private set
    var connecting by mutableStateOf(false); private set
    var connectError by mutableStateOf<String?>(null); private set
    var hostKeyPrompt by mutableStateOf<HostKeyPrompt?>(null); private set

    private var connection: SftpConnection? = null
    private var connectJob: Job? = null
    var activeProfile by mutableStateOf<Profile?>(null); private set

    // ---- Browser state ----
    var currentPath by mutableStateOf("/"); private set
    var files by mutableStateOf<List<RemoteFile>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var showHidden by mutableStateOf(false)
    var pendingUpload by mutableStateOf<PendingUpload?>(null); private set
    val transfers = mutableStateListOf<Transfer>()

    val visibleFiles: List<RemoteFile>
        get() = if (showHidden) files else files.filterNot { it.name.startsWith(".") }

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    private fun message(text: String, openUri: Uri? = null, mime: String? = null) {
        messageChannel.trySend(UiMessage(text, openUri, mime))
    }

    // ================= Profiles =================

    fun editProfile(profile: Profile?) {
        screen = Screen.Editor(profile)
    }

    fun closeEditor() {
        screen = Screen.Profiles
    }

    /** Saves a profile. [newKey] is the content of a newly imported private key, if any. */
    fun saveProfile(profile: Profile, newKey: ByteArray?) {
        val id = profile.id.ifEmpty { UUID.randomUUID().toString() }
        val saved = profile.copy(id = id)
        viewModelScope.launch(Dispatchers.IO) {
            if (saved.authType == AuthType.KEY && newKey != null) store.writeKey(id, newKey)
            if (saved.authType == AuthType.PASSWORD) store.deleteKey(id)
        }
        val list = profiles.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) list[idx] = saved else list.add(saved)
        profiles = list
        store.save(list)
        screen = Screen.Profiles
    }

    fun deleteProfile(profile: Profile) {
        profiles = profiles.filterNot { it.id == profile.id }
        store.save(profiles)
        viewModelScope.launch(Dispatchers.IO) { store.deleteKey(profile.id) }
    }

    // ================= Connecting =================

    fun connect(profile: Profile, secret: String) {
        if (connecting) return
        connecting = true
        connectError = null
        while (messageChannel.tryReceive().isSuccess) Unit // drop messages from a previous session
        connectJob = viewModelScope.launch {
            try {
                val key = if (profile.authType == AuthType.KEY) {
                    withContext(Dispatchers.IO) {
                        val f = store.keyFile(profile.id)
                        if (!f.exists()) throw IllegalStateException("No private key imported for this connection. Edit it and import a key.")
                        f.readBytes()
                    }
                } else null
                val params = ConnectParams(
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    password = if (profile.authType == AuthType.PASSWORD) secret else null,
                    privateKey = key,
                    keyPassphrase = if (profile.authType == AuthType.KEY) secret else null,
                )
                val conn = SftpConnection.connect(params, knownHosts, ::askHostKey)
                connection = conn
                activeProfile = profile
                files = emptyList()
                currentPath = conn.homeDirectory
                screen = Screen.Browser
                openDirectory(conn.homeDirectory)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                connectError = friendlyConnectError(e, profile)
            } finally {
                connecting = false
            }
        }
    }

    fun cancelConnect() {
        connectJob?.cancel()
        connecting = false
    }

    fun dismissConnectError() {
        connectError = null
    }

    /** Called from the SSH thread; suspends until the user answers the host key dialog. */
    private suspend fun askHostKey(message: String): Boolean = withContext(Dispatchers.Main) {
        val answer = CompletableDeferred<Boolean>()
        hostKeyPrompt = HostKeyPrompt(message, answer)
        try {
            answer.await()
        } finally {
            hostKeyPrompt = null
        }
    }

    fun answerHostKey(accept: Boolean) {
        hostKeyPrompt?.answer?.complete(accept)
    }

    fun disconnect() {
        transfers.filter { it.status == TransferStatus.Running }.forEach { it.job?.cancel() }
        val conn = connection
        connection = null
        viewModelScope.launch(Dispatchers.IO) { conn?.close() }
        activeProfile = null
        files = emptyList()
        pendingUpload = null
        screen = Screen.Profiles
    }

    private fun friendlyConnectError(e: Throwable, profile: Profile): String {
        val msg = e.message.orEmpty()
        val cause = generateSequence(e) { it.cause }.toList()
        return when {
            cause.any { it is UnknownHostException } -> "Unknown host \"${profile.host}\". Check the address and your internet connection."
            cause.any { it is ConnectException || it is NoRouteToHostException } -> "Could not reach ${profile.host}:${profile.port}. Is the server running and the port correct?"
            cause.any { it is SocketTimeoutException } || msg.contains("timeout", true) -> "Connection timed out."
            msg.startsWith("Auth fail") || msg.startsWith("Auth cancel") ->
                if (profile.authType == AuthType.KEY) "Authentication failed. Check the username and that this key is authorised on the server."
                else "Authentication failed. Check the username and password."
            msg.contains("passphrase", true) -> "Wrong passphrase for the private key."
            msg.contains("invalid privatekey", true) -> "The private key could not be read. Use an OpenSSH or PEM private key file."
            msg.contains("reject HostKey") || msg.contains("HostKey has been changed") -> "Connection cancelled: the server's host key was not accepted."
            e is IllegalStateException -> msg
            else -> "Connection failed: ${msg.ifBlank { e.javaClass.simpleName }}"
        }
    }

    // ================= Browsing =================

    fun openDirectory(path: String) {
        val conn = connection ?: return
        viewModelScope.launch {
            loading = true
            try {
                val canonical = conn.canonicalize(path)
                files = conn.list(canonical)
                currentPath = canonical
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                handleError("Could not open $path", e)
            } finally {
                loading = false
            }
        }
    }

    fun refresh() = openDirectory(currentPath)

    fun goUp() = openDirectory(RemotePaths.parent(currentPath))

    fun goHome() {
        connection?.let { openDirectory(it.homeDirectory) }
    }

    fun createFolder(name: String) = fileOp("Could not create folder") { conn ->
        conn.mkdir(RemotePaths.join(currentPath, name.trim()))
    }

    fun rename(file: RemoteFile, newName: String) = fileOp("Could not rename ${file.name}") { conn ->
        conn.rename(file.path, RemotePaths.join(RemotePaths.parent(file.path), newName.trim()))
    }

    fun delete(file: RemoteFile) = fileOp("Could not delete ${file.name}") { conn ->
        conn.delete(file.path, file.isDirectory && !file.isLink)
        message("Deleted ${file.name}")
    }

    private fun fileOp(errorText: String, block: suspend (SftpConnection) -> Unit) {
        val conn = connection ?: return
        viewModelScope.launch {
            loading = true
            try {
                block(conn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                handleError(errorText, e)
            } finally {
                loading = false
            }
            refresh()
        }
    }

    private fun handleError(prefix: String, e: Throwable) {
        val conn = connection
        if (conn != null && !conn.isConnected) {
            disconnect()
            connectError = "The connection to the server was lost."
            return
        }
        val detail = when {
            e is SftpException && e.id == 2 -> "not found" // SSH_FX_NO_SUCH_FILE
            e is SftpException && e.id == 3 -> "permission denied" // SSH_FX_PERMISSION_DENIED
            e is SftpException && e.id == 4 -> "operation failed (is the folder empty, or does the name already exist?)"
            e is JSchException -> e.message ?: "SSH error"
            else -> e.message ?: e.javaClass.simpleName
        }
        message("$prefix: $detail")
    }

    // ================= Transfers =================

    fun download(file: RemoteFile) {
        val conn = connection ?: return
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        val t = Transfer(nextTransferId++, file.name, isUpload = false, mimeType = DeviceFiles.mimeTypeFor(file.name))
        transfers.add(0, t)
        t.job = viewModelScope.launch {
            var uri: Uri? = null
            try {
                transferSlots.withPermit {
                    uri = withContext(Dispatchers.IO) { DeviceFiles.createDownload(resolver, file.name) }
                        ?: throw IllegalStateException("Could not create file in Downloads")
                    withContext(Dispatchers.IO) {
                        val out = resolver.openOutputStream(uri!!) ?: throw IllegalStateException("Could not write to Downloads")
                        out.use { conn.download(file.path, it) { d, total -> t.done = d; t.total = total } }
                        DeviceFiles.finishDownload(resolver, uri!!)
                    }
                }
                t.localUri = uri
                t.status = TransferStatus.Done
                message("Saved ${file.name} to Download/${DeviceFiles.DOWNLOAD_SUBFOLDER}", uri, t.mimeType)
            } catch (e: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) {
                    uri?.let { runCatching { resolver.delete(it, null, null) } }
                }
                if (e is CancellationException) {
                    t.status = TransferStatus.Cancelled
                    throw e
                }
                t.status = TransferStatus.Failed
                t.error = e.message
                handleError("Download of ${file.name} failed", e)
            }
        }
    }

    /** Starts the upload flow for files picked on the device; asks before replacing files. */
    fun requestUpload(uris: List<Uri>) {
        if (connection == null || uris.isEmpty()) return
        val resolver = getApplication<Application>().contentResolver
        val targetDir = currentPath
        val existing = files.map { it.name }.toSet()
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { uris.map { DeviceFiles.describe(resolver, it) } }
            val conflicts = picked.map { it.name }.filter { it in existing }
            if (conflicts.isEmpty()) startUploads(picked, targetDir)
            else pendingUpload = PendingUpload(picked, conflicts, targetDir)
        }
    }

    /** replace = true overwrites existing files, false skips them, null cancels. */
    fun resolvePendingUpload(replace: Boolean?) {
        val p = pendingUpload ?: return
        pendingUpload = null
        when (replace) {
            true -> startUploads(p.files, p.targetDir)
            false -> startUploads(p.files.filterNot { it.name in p.conflicts }, p.targetDir)
            null -> Unit
        }
    }

    private fun startUploads(picked: List<DeviceFiles.PickedFile>, targetDir: String) {
        val conn = connection ?: return
        val resolver = getApplication<Application>().contentResolver
        for (f in picked) {
            val t = Transfer(nextTransferId++, f.name, isUpload = true, mimeType = DeviceFiles.mimeTypeFor(f.name))
            transfers.add(0, t)
            val remote = RemotePaths.join(targetDir, f.name)
            t.job = viewModelScope.launch {
                try {
                    transferSlots.withPermit {
                        withContext(Dispatchers.IO) {
                            val input = resolver.openInputStream(f.uri) ?: throw IllegalStateException("Could not read ${f.name}")
                            input.use { conn.upload(it, remote, f.size) { d, total -> t.done = d; t.total = total } }
                        }
                    }
                    t.status = TransferStatus.Done
                    if (currentPath == targetDir) refresh()
                } catch (e: Throwable) {
                    if (t.done > 0) {
                        // Bytes were written, so the remote file is incomplete: remove it.
                        withContext(NonCancellable) { runCatching { conn.delete(remote, false) } }
                    }
                    if (e is CancellationException) {
                        t.status = TransferStatus.Cancelled
                        throw e
                    }
                    t.status = TransferStatus.Failed
                    t.error = e.message
                    handleError("Upload of ${f.name} failed", e)
                }
            }
        }
    }

    fun cancelTransfer(t: Transfer) {
        t.job?.cancel()
    }

    fun clearFinishedTransfers() {
        transfers.removeAll { it.status != TransferStatus.Running }
    }

    override fun onCleared() {
        connection?.close()
    }
}
