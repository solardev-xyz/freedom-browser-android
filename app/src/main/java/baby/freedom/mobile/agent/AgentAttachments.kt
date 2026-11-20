package baby.freedom.mobile.agent

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/*
 * Files the user attaches to a chat (#601), desktop's
 * `conversation-attachment-store.js`: images, text files and PDFs,
 * picked in Android's file picker. A pick is copied at once into the
 * app's own storage ("staged", shown under the composer), and sending
 * the message moves the copies into the chat's folder: frozen private
 * snapshots, as on desktop, so the picker's grant is never kept and a
 * file changed later isn't what the model reads. The agent sees only an
 * opaque id, the file's name, size and kind; never a path or a URI.
 *
 * Everything lives in `no_backup/agent-attachments/` (`chats/<chat>/`,
 * `staged/`), which no backup or device transfer copies. A chat's
 * folder goes with the chat; staged copies go with the process (a pick
 * never sent is gone after a restart); a folder whose chat no longer
 * exists is swept at start. Desktop's folder grants (read-only access to
 * a live directory) aren't here: Android has no path to grant, and a
 * SAF tree grant would outlive the chat.
 */

internal enum class AttachmentKind(val wire: String) {
    Text("text"),
    Image("image"),
    Pdf("pdf"),
    ;

    companion object {
        fun of(wire: Any?): AttachmentKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** A file attached to a chat: [id] is the opaque `attachment_…` id the model uses. */
internal data class Attachment(
    val id: String,
    val name: String,
    val bytes: Long,
    val mimeType: String,
    val kind: AttachmentKind,
) {
    /** What the model is told of it (desktop's `publicResource`). */
    fun json(): JSONObject = JSONObject()
        .put("resourceId", id)
        .put("kind", "file")
        .put("name", name)
        .put("bytes", bytes)
        .put("mimeType", mimeType)
        .put("category", kind.wire)

    companion object {
        fun fromJson(o: JSONObject?): Attachment? {
            o ?: return null
            val id = (o.opt("resourceId") as? String)?.takeIf { AttachmentRules.RESOURCE_ID.matches(it) } ?: return null
            val kind = AttachmentKind.of(o.opt("category")) ?: return null
            val mime = (o.opt("mimeType") as? String)?.takeIf { AttachmentRules.mimeAllowed(kind, it) } ?: return null
            val bytes = (o.opt("bytes") as? Number)?.toLong()?.takeIf { it in 0..AttachmentRules.MAX_FILE_BYTES } ?: return null
            return Attachment(id, AttachmentRules.cleanName(o.opt("name") as? String), bytes, mime, kind)
        }

        fun listJson(attachments: List<Attachment>): String = JSONArray(attachments.map { it.json() }).toString()

        /** A turn's stored list ([ChatTurn.attachments]); anything unusable in it is left out. */
        fun parseList(json: String?): List<Attachment> {
            if (json.isNullOrEmpty()) return emptyList()
            val arr = try {
                JSONArray(json)
            } catch (e: Exception) {
                return emptyList()
            } catch (e: StackOverflowError) {
                return emptyList()
            }
            return (0 until minOf(arr.length(), AttachmentRules.MAX_SELECTIONS)).mapNotNull { fromJson(arr.optJSONObject(it)) }
        }
    }
}

/** A picked file waiting under the composer for its message to be sent. */
internal data class StagedAttachment(
    val id: String,
    val name: String,
    val bytes: Long,
    val mimeType: String,
    val kind: AttachmentKind,
)

/** Why a pick wasn't attached: shown to the user, with the file's name where it has one. */
internal enum class StageRefusal {
    /** Already [AttachmentRules.MAX_SELECTIONS] files on this message. */
    TooMany,

    /** Over [AttachmentRules.MAX_FILE_BYTES]. */
    TooLarge,

    /** The message's files would come to over [AttachmentRules.MAX_TOTAL_BYTES]. */
    TotalTooLarge,

    /** Not a text file, image or PDF. */
    Unsupported,

    /** Named as an image, but not a JPEG, PNG or WebP that decodes. */
    NotAnImage,

    /** An image over [AttachmentRules.MAX_IMAGE_BYTES], the most a model is sent. */
    ImageTooLarge,

    /** It couldn't be read, or the phone couldn't keep a copy. */
    Unreadable,
}

internal sealed interface StageResult {
    data class Staged(val attachment: StagedAttachment) : StageResult
    data class Refused(val reason: StageRefusal, val name: String) : StageResult
}

/** A piece of a text attachment: bytes [start] to [end] of [total]. */
internal data class TextSlice(val text: String, val start: Long, val end: Long, val total: Long)

/** Why an attachment couldn't be read: fixed words for the model ([AttachmentTools]). */
internal class AttachmentException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NotFound, Unavailable, WrongKind, ImageTooLarge }
}

/** Desktop's caps and file kinds (`conversation-attachment-store.js`). */
internal object AttachmentRules {
    const val MAX_SELECTIONS = 10
    const val MAX_FILE_BYTES = 20L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 50L * 1024 * 1024
    const val MAX_IMAGE_BYTES = 8L * 1024 * 1024

    /**
     * Bytes of text one read returns. Desktop's is 256 KiB; here it stays
     * under the tool registry's cut ([ToolRegistry.MAX_OUTPUT_CHARS]), so
     * the offset a read gives for what comes next is true.
     */
    const val MAX_TEXT_READ_BYTES = 48 * 1024
    const val MAX_NAME_CHARS = 240

    val RESOURCE_ID = Regex("attachment_[a-f0-9]{20}")
    val SELECTION_ID = Regex("selection_[a-f0-9]{20}")

    /** A chat's id as [AgentChats] makes them. */
    val CHAT_ID = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")

    val IMAGE_TYPES = mapOf(".jpg" to "image/jpeg", ".jpeg" to "image/jpeg", ".png" to "image/png", ".webp" to "image/webp")
    val TEXT_EXTENSIONS = setOf(
        ".c", ".cc", ".conf", ".cpp", ".css", ".csv", ".env", ".go", ".graphql", ".h", ".hpp",
        ".html", ".ini", ".java", ".js", ".json", ".jsx", ".log", ".md", ".mjs", ".py", ".rb",
        ".rs", ".sh", ".sql", ".svg", ".toml", ".ts", ".tsx", ".txt", ".xml", ".yaml", ".yml",
        // Not on desktop's list, common on a phone.
        ".kt", ".kts",
    )
    private val TEXT_MIME = setOf("application/json", "application/xml", "application/javascript", "application/x-yaml", "application/toml")

    /**
     * What [name] is, by its extension as on desktop; a name with no
     * extension Freedom knows goes by the picker's [mimeType]. Null: not
     * something the agent reads. The type is the one the model is told;
     * an image's is checked against its bytes when it's picked.
     */
    fun classify(name: String, mimeType: String?): Pair<AttachmentKind, String>? {
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0) name.substring(dot).lowercase() else ""
        IMAGE_TYPES[ext]?.let { return AttachmentKind.Image to it }
        if (ext == ".pdf") return AttachmentKind.Pdf to "application/pdf"
        if (ext in TEXT_EXTENSIONS) return AttachmentKind.Text to "text/plain"
        // Not an extension Freedom knows ("Notes 20.11.2026", "report.v2", none): the picker's type.
        val mime = mimeType?.lowercase()?.substringBefore(';')?.trim() ?: return null
        if (mime in IMAGE_TYPES.values) return AttachmentKind.Image to mime
        if (mime == "application/pdf") return AttachmentKind.Pdf to mime
        if (mime.startsWith("text/") || mime in TEXT_MIME) return AttachmentKind.Text to "text/plain"
        return null
    }

    fun mimeAllowed(kind: AttachmentKind, mime: String): Boolean = when (kind) {
        AttachmentKind.Image -> mime in IMAGE_TYPES.values
        AttachmentKind.Pdf -> mime == "application/pdf"
        AttachmentKind.Text -> mime == "text/plain"
    }

    /**
     * A file name as it's shown and told to the model: what draws nothing
     * or reorders the text around it (controls, format and bidi
     * characters, separators, lone surrogates, unassigned code points;
     * emoji joiners and tags aside) is left out, and it's cut at
     * [MAX_NAME_CHARS] code points. The name is still the file's own:
     * untrusted, a downloaded file's name is the site's.
     */
    fun cleanName(raw: String?): String {
        val s = raw.orEmpty()
        val out = StringBuilder()
        var i = 0
        var n = 0
        while (i < s.length && n < MAX_NAME_CHARS) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            // Joiners and tag characters build emoji; they don't hide or reorder anything.
            val emoji = cp == 0x200C || cp == 0x200D || cp in 0xE0020..0xE007F
            if (!emoji) when (Character.getType(cp).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                Character.SURROGATE, Character.UNASSIGNED, Character.PRIVATE_USE,
                -> continue
            }
            out.appendCodePoint(cp)
            n++
        }
        return out.toString().trim().ifEmpty { "Untitled" }
    }

    /**
     * Bytes [offset] to at most [limit] past it of a UTF-8 file of [total]
     * bytes, given [read] for a range: whole characters only, so a
     * character a bound falls inside starts the next read instead.
     */
    fun textSlice(total: Long, offset: Long, limit: Int, read: (Long, Int) -> ByteArray): TextSlice {
        val start0 = offset.coerceIn(0, total)
        val length = minOf(limit.toLong(), total - start0).toInt()
        // Three bytes of look-behind, so a start inside a character skips to the next one.
        val back = minOf(3L, start0).toInt()
        val raw = read(start0 - back, length + back)
        var s = back
        // A start on a continuation byte (10xxxxxx) is inside a character.
        while (s < raw.size && s < back + 3 && (raw[s].toInt() and 0xC0) == 0x80 && start0 > 0) s++
        var e = raw.size
        if (start0 - back + raw.size < total) {
            // Ends inside a character: back to where it starts.
            var lead = e - 1
            while (lead > s && (raw[lead].toInt() and 0xC0) == 0x80) lead--
            if (lead >= s) {
                val b = raw[lead].toInt() and 0xFF
                val need = when {
                    b < 0x80 -> 1
                    b >= 0xF0 -> 4
                    b >= 0xE0 -> 3
                    b >= 0xC0 -> 2
                    else -> 1
                }
                if (lead + need > e) e = lead
            }
        }
        if (e < s) e = s
        val text = String(raw, s, e - s, Charsets.UTF_8)
        val start = start0 - back + s
        return TextSlice(text, start, start + (e - s), total)
    }
}

/**
 * The store. [root] is its folder; [imageType] reads an image's real
 * type from its bytes (null: not a JPEG, PNG or WebP that decodes).
 */
internal class AgentAttachments(
    private val root: File,
    private val imageType: (File) -> String?,
) {
    private val stagedDir = File(root, "staged")
    private val chatsDir = File(root, "chats")
    private val lock = Mutex()
    private val random = SecureRandom()
    private val _staged = MutableStateFlow<List<StagedAttachment>>(emptyList())

    /** Files picked for the message being written, in the order picked. */
    val staged: StateFlow<List<StagedAttachment>> = _staged.asStateFlow()

    init {
        // A pick never sent doesn't outlive the process that made it.
        stagedDir.deleteRecursively()
    }

    private fun newId(prefix: String): String {
        val bytes = ByteArray(10).also(random::nextBytes)
        return prefix + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun stagedFile(id: String) = File(stagedDir, id)

    private fun chatDir(sessionId: String): File {
        require(AttachmentRules.CHAT_ID.matches(sessionId)) { "not a chat id" }
        return File(chatsDir, sessionId)
    }

    /**
     * Copies a picked file ([open], named [name] by the picker, of type
     * [mimeType]) into staging, counting the bytes as they come: the
     * picker's own size and type aren't trusted. Off the main thread.
     */
    suspend fun stage(name: String?, mimeType: String?, open: () -> InputStream?): StageResult = withContext(Dispatchers.IO) {
        val shown = AttachmentRules.cleanName(name)
        lock.withLock {
            if (_staged.value.size >= AttachmentRules.MAX_SELECTIONS) return@withLock StageResult.Refused(StageRefusal.TooMany, shown)
            val (kind, typed) = AttachmentRules.classify(shown, mimeType)
                ?: return@withLock StageResult.Refused(StageRefusal.Unsupported, shown)
            val id = newId("selection_")
            val file = stagedFile(id)
            val cap = if (kind == AttachmentKind.Image) AttachmentRules.MAX_IMAGE_BYTES else AttachmentRules.MAX_FILE_BYTES
            val copied = try {
                stagedDir.mkdirs()
                val input = open() ?: return@withLock StageResult.Refused(StageRefusal.Unreadable, shown)
                input.use { copyAtMost(it, file, cap) }
            } catch (e: IOException) {
                file.delete()
                return@withLock StageResult.Refused(StageRefusal.Unreadable, shown)
            } catch (e: SecurityException) {
                file.delete()
                return@withLock StageResult.Refused(StageRefusal.Unreadable, shown)
            }
            if (copied == null) {
                file.delete()
                val reason = if (kind == AttachmentKind.Image) StageRefusal.ImageTooLarge else StageRefusal.TooLarge
                return@withLock StageResult.Refused(reason, shown)
            }
            if (_staged.value.sumOf { it.bytes } + copied > AttachmentRules.MAX_TOTAL_BYTES) {
                file.delete()
                return@withLock StageResult.Refused(StageRefusal.TotalTooLarge, shown)
            }
            var mime = typed
            if (kind == AttachmentKind.Image) {
                // An image the provider can't decode fails the whole turn: checked now.
                mime = imageType(file)?.takeIf { it in AttachmentRules.IMAGE_TYPES.values } ?: run {
                    file.delete()
                    return@withLock StageResult.Refused(StageRefusal.NotAnImage, shown)
                }
            }
            val staged = StagedAttachment(id, shown, copied, mime, kind)
            _staged.update { it + staged }
            StageResult.Staged(staged)
        }
    }

    /** Copies [input] to [file]; null (and nothing kept) once it passes [cap] bytes. */
    private fun copyAtMost(input: InputStream, file: File, cap: Long): Long? {
        var total = 0L
        file.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > cap) return null
                out.write(buf, 0, n)
            }
        }
        return total
    }

    /** Removes a staged file (its chip's ×). */
    suspend fun unstage(id: String): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val found = _staged.value.any { it.id == id }
            if (found) {
                _staged.update { list -> list.filterNot { it.id == id } }
                stagedFile(id).delete()
            }
            found
        }
    }

    /** Removes every staged file. */
    suspend fun clearStaged() = withContext(Dispatchers.IO) {
        lock.withLock {
            for (s in _staged.value) stagedFile(s.id).delete()
            _staged.value = emptyList()
        }
    }

    /**
     * Moves the staged files [ids] into chat [sessionId]'s folder for the
     * message being sent, and returns them as the chat's attachments.
     * Null if one of them is no longer staged (removed meanwhile), or
     * they couldn't be moved: then nothing moved.
     */
    suspend fun consume(sessionId: String, ids: List<String>): List<Attachment>? = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        lock.withLock {
            val picked = ids.distinct().map { id -> _staged.value.firstOrNull { it.id == id } ?: return@withLock null }
            val dir = chatDir(sessionId)
            val current = readManifest(dir)
            val moved = ArrayList<Pair<StagedAttachment, Attachment>>()
            fun undo() {
                for ((s, a) in moved) File(dir, a.id).renameTo(stagedFile(s.id))
                if (current.isEmpty()) dir.deleteRecursively()
            }
            dir.mkdirs()
            for (s in picked) {
                val a = Attachment(newId("attachment_"), s.name, s.bytes, s.mimeType, s.kind)
                if (!stagedFile(s.id).renameTo(File(dir, a.id))) {
                    undo()
                    return@withLock null
                }
                moved += s to a
            }
            val added = moved.map { it.second }
            if (!writeManifest(dir, current + added)) {
                undo()
                return@withLock null
            }
            val gone = picked.map { it.id }.toSet()
            _staged.update { list -> list.filterNot { it.id in gone } }
            added
        }
    }

    /**
     * Puts [attachments] back under the composer: their message couldn't
     * be saved after all. Any whose file is gone (the chat was deleted
     * meanwhile) is dropped.
     */
    suspend fun unconsume(sessionId: String, attachments: List<Attachment>) = withContext(Dispatchers.IO) {
        if (attachments.isEmpty()) return@withContext
        lock.withLock {
            val dir = chatDir(sessionId)
            val back = ArrayList<StagedAttachment>()
            stagedDir.mkdirs()
            for (a in attachments) {
                val s = StagedAttachment(newId("selection_"), a.name, a.bytes, a.mimeType, a.kind)
                if (File(dir, a.id).renameTo(stagedFile(s.id))) back += s
            }
            val ids = attachments.map { it.id }.toSet()
            val left = readManifest(dir).filterNot { it.id in ids }
            if (left.isEmpty()) dir.deleteRecursively() else writeManifest(dir, left)
            _staged.update { (it + back).take(AttachmentRules.MAX_SELECTIONS) }
        }
    }

    /** Chat [sessionId]'s attachments, oldest first. */
    suspend fun list(sessionId: String): List<Attachment> = withContext(Dispatchers.IO) {
        lock.withLock { readManifest(chatDir(sessionId)) }
    }

    private fun find(sessionId: String, id: String): Pair<Attachment, File> {
        val dir = chatDir(sessionId)
        val a = readManifest(dir).firstOrNull { it.id == id } ?: throw AttachmentException(AttachmentException.Reason.NotFound)
        val file = File(dir, a.id)
        if (!file.isFile) throw AttachmentException(AttachmentException.Reason.Unavailable)
        return a to file
    }

    /** [id]'s attachment and its file, for reading; throws [AttachmentException]. */
    suspend fun open(sessionId: String, id: String): Pair<Attachment, File> = withContext(Dispatchers.IO) {
        lock.withLock { find(sessionId, id) }
    }

    /** Bytes [offset] onwards (at most [limit]) of a text attachment. */
    suspend fun readText(sessionId: String, id: String, offset: Long, limit: Int): Pair<Attachment, TextSlice> =
        withContext(Dispatchers.IO) {
            val (a, file) = lock.withLock { find(sessionId, id) }
            if (a.kind != AttachmentKind.Text) throw AttachmentException(AttachmentException.Reason.WrongKind)
            try {
                RandomAccessFile(file, "r").use { raf ->
                    val total = raf.length()
                    a to AttachmentRules.textSlice(total, offset, limit.coerceIn(4, AttachmentRules.MAX_TEXT_READ_BYTES)) { at, n ->
                        val buf = ByteArray(n)
                        raf.seek(at)
                        var got = 0
                        while (got < n) {
                            val r = raf.read(buf, got, n - got)
                            if (r < 0) break
                            got += r
                        }
                        if (got < n) buf.copyOf(got) else buf
                    }
                }
            } catch (e: IOException) {
                throw AttachmentException(AttachmentException.Reason.Unavailable)
            }
        }

    /** An image attachment, for a model that takes images. */
    suspend fun readImage(sessionId: String, id: String): Pair<Attachment, ImagePart> = withContext(Dispatchers.IO) {
        val (a, file) = lock.withLock { find(sessionId, id) }
        if (a.kind != AttachmentKind.Image) throw AttachmentException(AttachmentException.Reason.WrongKind)
        if (file.length() > AttachmentRules.MAX_IMAGE_BYTES) throw AttachmentException(AttachmentException.Reason.ImageTooLarge)
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            throw AttachmentException(AttachmentException.Reason.Unavailable)
        }
        a to ImagePart(a.mimeType, java.util.Base64.getEncoder().encodeToString(bytes))
    }

    /** Deletes chat [sessionId]'s attachments. */
    suspend fun deleteChat(sessionId: String) = withContext(Dispatchers.IO) {
        if (!AttachmentRules.CHAT_ID.matches(sessionId)) return@withContext
        lock.withLock { chatDir(sessionId).deleteRecursively() }
    }

    /** Deletes every chat's attachments (staged ones stay: they belong to the message being written). */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        lock.withLock { chatsDir.deleteRecursively() }
    }

    /** Whether any chat has a folder: nothing to sweep otherwise. */
    fun hasChats(): Boolean = chatsDir.list()?.isNotEmpty() == true

    /**
     * Deletes the folders of chats not in [existing]: a chat deleted
     * while its folder couldn't be, or a message whose save was cut off
     * by the app stopping. Only folders last changed before [before]
     * (ms, wall clock: when [existing] was read): one a send filled since
     * belongs to a chat [existing] couldn't know yet. Returns how many went.
     */
    suspend fun sweep(existing: Set<String>, before: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            var n = 0
            for (dir in chatsDir.listFiles().orEmpty()) {
                if (dir.name !in existing && dir.lastModified() < before && dir.deleteRecursively()) n++
            }
            n
        }
    }

    private fun readManifest(dir: File): List<Attachment> {
        val file = File(dir, MANIFEST)
        if (!file.isFile) return emptyList()
        val root = try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "an attachment manifest couldn't be read (${e.javaClass.simpleName})")
            return emptyList()
        } catch (e: StackOverflowError) {
            return emptyList()
        }
        val arr = root.optJSONArray("resources") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { Attachment.fromJson(arr.optJSONObject(it)) }.distinctBy { it.id }
    }

    private fun writeManifest(dir: File, attachments: List<Attachment>): Boolean = try {
        val tmp = File(dir, "$MANIFEST.tmp")
        tmp.writeText(JSONObject().put("version", 1).put("resources", JSONArray(attachments.map { it.json() })).toString())
        tmp.renameTo(File(dir, MANIFEST))
    } catch (e: IOException) {
        false
    }

    companion object {
        private const val TAG = "AgentAttachments"
        private const val MANIFEST = "manifest.json"
        const val DIR = "agent-attachments"

        @Volatile
        private var instance: AgentAttachments? = null

        /**
         * At the app's start (MainActivity): staged copies a process left
         * go (the store's first use does that), and so do the folders of
         * chats that are gone, without opening the chat database when no
         * chat has files. Off the main thread.
         */
        suspend fun atLaunch(context: Context) {
            val store = get(context)
            if (!store.hasChats()) return
            val before = System.currentTimeMillis()
            try {
                store.sweep(AgentDatabase.get(context).chats().sessionIds().toSet(), before)
            } catch (e: Exception) {
                Log.w(TAG, "sweeping chat attachments failed (${e.javaClass.simpleName})")
            }
        }

        fun get(context: Context): AgentAttachments = instance ?: synchronized(this) {
            instance ?: AgentAttachments(File(context.applicationContext.noBackupFilesDir, DIR), ::decodedImageType)
                .also { instance = it }
        }

        /** An image's type from its header, if Android can decode it and it's one a model takes. */
        fun decodedImageType(file: File): String? {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            return options.outMimeType?.lowercase()
        }
    }
}
