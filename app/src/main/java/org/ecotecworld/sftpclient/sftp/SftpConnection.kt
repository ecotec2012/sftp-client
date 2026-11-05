package org.ecotecworld.sftpclient.sftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/** A file or directory on the remote server. */
data class RemoteFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isLink: Boolean,
    val size: Long,
    val modifiedSeconds: Long,
    val permissions: String,
)

class ConnectParams(
    val host: String,
    val port: Int,
    val username: String,
    val password: String?,
    val privateKey: ByteArray?,
    val keyPassphrase: String?,
)

/** Path helpers for remote (always '/'-separated) paths. */
object RemotePaths {
    fun join(dir: String, name: String): String = if (dir.endsWith("/")) dir + name else "$dir/$name"

    fun parent(path: String): String {
        val trimmed = path.trimEnd('/')
        val idx = trimmed.lastIndexOf('/')
        return if (idx <= 0) "/" else trimmed.substring(0, idx)
    }

    fun isValidName(name: String): Boolean =
        name.isNotBlank() && !name.contains('/') && name != "." && name != ".."
}

/**
 * One SSH session with an SFTP channel used for browsing. Each upload/download opens its own
 * SFTP channel on the same session, so browsing stays responsive while files transfer.
 */
class SftpConnection private constructor(
    private val session: Session,
    private val channel: ChannelSftp,
    val homeDirectory: String,
) {
    private val browseLock = Mutex()

    val isConnected: Boolean get() = session.isConnected && channel.isConnected

    suspend fun canonicalize(path: String): String = browse { it.realpath(path) }

    suspend fun list(path: String): List<RemoteFile> = browse { ch ->
        @Suppress("UNCHECKED_CAST")
        val entries = ch.ls(path) as java.util.Vector<ChannelSftp.LsEntry>
        entries
            .filter { it.filename != "." && it.filename != ".." }
            .map { e ->
                val full = RemotePaths.join(path, e.filename)
                val attrs = e.attrs
                // Follow symlinks so links to folders can be opened.
                val isDir = if (attrs.isLink) {
                    runCatching { ch.stat(full).isDir }.getOrDefault(false)
                } else {
                    attrs.isDir
                }
                RemoteFile(
                    name = e.filename,
                    path = full,
                    isDirectory = isDir,
                    isLink = attrs.isLink,
                    size = attrs.size,
                    modifiedSeconds = attrs.mTime.toLong() and 0xFFFFFFFFL,
                    permissions = attrs.permissionsString ?: "",
                )
            }
            .sortedWith(compareBy<RemoteFile>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    suspend fun exists(path: String): Boolean = browse { ch ->
        try {
            ch.lstat(path); true
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw e
        }
    }

    suspend fun mkdir(path: String) = browse { it.mkdir(path) }

    suspend fun rename(from: String, to: String) = browse { it.rename(from, to) }

    /** Deletes a file, or a folder and everything inside it. */
    suspend fun delete(path: String, isDirectory: Boolean) = browse { ch -> deleteRecursive(ch, path, isDirectory) }

    private fun deleteRecursive(ch: ChannelSftp, path: String, isDirectory: Boolean) {
        if (!isDirectory) {
            ch.rm(path)
            return
        }
        @Suppress("UNCHECKED_CAST")
        val entries = ch.ls(path) as java.util.Vector<ChannelSftp.LsEntry>
        for (e in entries) {
            if (e.filename == "." || e.filename == "..") continue
            // Use lstat attrs: a symlink to a folder is removed as a link, never followed.
            deleteRecursive(ch, RemotePaths.join(path, e.filename), e.attrs.isDir && !e.attrs.isLink)
        }
        ch.rmdir(path)
    }

    /**
     * Downloads [remotePath] into [out]. Cancelling the calling coroutine stops the transfer
     * and throws CancellationException.
     */
    suspend fun download(remotePath: String, out: OutputStream, onProgress: (done: Long, total: Long) -> Unit) =
        transfer { ch, job ->
            val total = ch.stat(remotePath).size
            onProgress(0, total)
            ch.get(remotePath, out, ProgressMonitor(total, job, onProgress))
        }

    /** Uploads [input] to [remotePath], replacing any existing file. */
    suspend fun upload(input: InputStream, remotePath: String, size: Long, onProgress: (done: Long, total: Long) -> Unit) =
        transfer { ch, job ->
            onProgress(0, size)
            ch.put(input, remotePath, ProgressMonitor(size, job, onProgress), ChannelSftp.OVERWRITE)
        }

    fun close() {
        runCatching { channel.disconnect() }
        runCatching { session.disconnect() }
    }

    private suspend fun <T> browse(block: (ChannelSftp) -> T): T = withContext(Dispatchers.IO) {
        browseLock.withLock { block(channel) }
    }

    private suspend fun transfer(block: (ChannelSftp, Job?) -> Unit) = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        val ch = session.openChannel("sftp") as ChannelSftp
        try {
            ch.connect(CONNECT_TIMEOUT_MS)
            block(ch, job)
        } finally {
            ch.disconnect()
        }
        // JSch returns normally when the progress monitor aborts, so surface cancellation here.
        coroutineContext.ensureActive()
    }

    private class ProgressMonitor(
        private val total: Long,
        private val job: Job?,
        private val onProgress: (Long, Long) -> Unit,
    ) : SftpProgressMonitor {
        private var done = 0L
        private var lastReport = 0L

        override fun init(op: Int, src: String?, dest: String?, max: Long) {}

        override fun count(count: Long): Boolean {
            done += count
            val now = System.nanoTime()
            if (now - lastReport > 100_000_000L) { // at most ~10 updates per second
                lastReport = now
                onProgress(done, total)
            }
            return job?.isActive != false
        }

        override fun end() = onProgress(done, total)
    }

    /** Supplies credentials to JSch and routes host key questions to the UI. */
    private class Prompter(
        private val password: String?,
        private val confirmHostKey: suspend (String) -> Boolean,
    ) : UserInfo, UIKeyboardInteractive {
        // Offer the password once per method; otherwise JSch retries the same wrong password
        // until the server disconnects with "Too many authentication failures".
        private var passwordOffered = false
        private var keyboardInteractiveOffered = false

        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = password
        override fun promptPassword(message: String?): Boolean {
            if (password == null || passwordOffered) return false
            passwordOffered = true
            return true
        }
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String): Boolean = runBlocking { confirmHostKey(message) }
        override fun showMessage(message: String?) {}

        override fun promptKeyboardInteractive(
            destination: String?, name: String?, instruction: String?,
            prompt: Array<out String>, echo: BooleanArray,
        ): Array<String>? {
            if (password == null || keyboardInteractiveOffered) return null
            keyboardInteractiveOffered = true
            return Array(prompt.size) { password }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000

        /**
         * Opens an SSH session and SFTP channel. Unknown or changed host keys are passed to
         * [confirmHostKey]; accepted keys are saved in [knownHostsFile].
         */
        suspend fun connect(
            params: ConnectParams,
            knownHostsFile: File,
            confirmHostKey: suspend (String) -> Boolean,
        ): SftpConnection = withContext(Dispatchers.IO) {
            if (!knownHostsFile.exists()) {
                knownHostsFile.parentFile?.mkdirs()
                knownHostsFile.createNewFile()
            }
            val jsch = JSch()
            jsch.setKnownHosts(knownHostsFile.absolutePath)
            params.privateKey?.let { key ->
                jsch.addIdentity("key", key.copyOf(), null, params.keyPassphrase?.takeIf { it.isNotEmpty() }?.toByteArray())
            }
            val session = jsch.getSession(params.username, params.host, params.port)
            params.password?.let { session.setPassword(it) }
            session.setConfig("StrictHostKeyChecking", "ask")
            session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
            session.userInfo = Prompter(params.password, confirmHostKey)
            session.serverAliveInterval = 30_000
            try {
                session.connect(CONNECT_TIMEOUT_MS)
                val ch = session.openChannel("sftp") as ChannelSftp
                ch.connect(CONNECT_TIMEOUT_MS)
                SftpConnection(session, ch, ch.home)
            } catch (e: Throwable) {
                session.disconnect()
                throw e
            }
        }
    }
}
