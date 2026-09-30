package zone.disinfo.wx.data

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Display snapshots only. Alert evaluation must always use a fresh network response. */
data class CachedPayload(val bytes: ByteArray, val fetchedAt: Long) {
    val ageMillis: Long
        get() = cacheAgeMillis(fetchedAt)
}

internal fun cacheAgeMillis(fetchedAt: Long, now: Long = System.currentTimeMillis()): Long =
    if (fetchedAt <= 0 || fetchedAt > now + 60_000) Long.MAX_VALUE
    else (now - fetchedAt).coerceAtLeast(0)

/**
 * One persistent budget for forecast, ensemble, and radar display data. Reads and writes are
 * serialized off Main; file modification times track LRU separately from the original fetch time.
 * Opening an app never scans or parses this store on Main. Android backup excludes these files.
 */
object DisplayCache {
    const val MAX_AGE_MILLIS = 7 * 24 * 3_600_000L
    const val RETENTION_MILLIS = MAX_AGE_MILLIS
    const val MAX_BYTES = 64L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    private const val MAGIC = 0x57584331
    private val lock = Mutex()
    @Volatile private var application: Context? = null
    @Volatile var generation: Long = 0L
        private set

    fun initialize(context: Context) {
        application = context.applicationContext
    }

    /** No network is an immediate cache-only path; transient server failures still retain data. */
    fun isOnline(): Boolean = application?.let {
        NetworkConnectivity.status(it) != NetworkAvailability.OFFLINE
    } ?: true

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        lock.withLock { directory()?.listFiles()?.sumOf { it.length() } ?: 0L }
    }

    suspend fun read(namespace: String, key: String): CachedPayload? = withContext(Dispatchers.IO) {
        lock.withLock {
            val directory = directory() ?: return@withLock null
            val identity = "$namespace\n$key"
            val file = File(directory, digest(identity))
            val payload = readFile(file, identity)
            if (payload == null) file.delete() else file.setLastModified(System.currentTimeMillis())
            payload
        }
    }

    suspend fun write(
        namespace: String,
        key: String,
        bytes: ByteArray,
        fetchedAt: Long = System.currentTimeMillis(),
        expectedGeneration: Long = generation,
    ) = withContext(Dispatchers.IO) {
        if (bytes.size > MAX_ENTRY_BYTES || cacheAgeMillis(fetchedAt) > RETENTION_MILLIS)
            return@withContext
        lock.withLock {
            if (generation != expectedGeneration) return@withLock
            val directory = directory() ?: return@withLock
            val identity = "$namespace\n$key"
            val name = digest(identity)
            val temporary = File(directory, "$name.tmp")
            try {
                if (!directory.exists() && !directory.mkdirs()) return@withLock
                DataOutputStream(temporary.outputStream().buffered()).use { output ->
                    output.writeInt(MAGIC)
                    output.writeLong(fetchedAt)
                    output.writeUTF(identity)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                }
                val destination = File(directory, name)
                if (!temporary.renameTo(destination)) temporary.delete()
                trim(directory)
            } catch (_: Exception) {
                temporary.delete()
                // Storage pressure must never break a successful weather request.
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock {
            generation++
            directory()?.listFiles()?.forEach { it.delete() }
        }
    }

    private fun directory(): File? = application?.let { File(it.filesDir, "display-cache-v1") }

    private fun readFile(file: File, expectedIdentity: String): CachedPayload? = try {
        if (!file.isFile || file.length() > MAX_ENTRY_BYTES + 65_550) null
        else DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != MAGIC) return@use null
            val fetchedAt = input.readLong()
            if (cacheAgeMillis(fetchedAt) > RETENTION_MILLIS || input.readUTF() != expectedIdentity)
                return@use null
            val count = input.readInt()
            if (count !in 0..MAX_ENTRY_BYTES) return@use null
            val bytes = ByteArray(count)
            input.readFully(bytes)
            CachedPayload(bytes, fetchedAt)
        }
    } catch (_: Exception) {
        null
    }

    private fun trim(directory: File) {
        val files = directory.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        var used = 0L
        for (file in files) {
            val expired = try {
                DataInputStream(file.inputStream().buffered()).use {
                    it.readInt() != MAGIC || cacheAgeMillis(it.readLong()) > RETENTION_MILLIS
                }
            } catch (_: Exception) { true }
            if (expired || used + file.length() > MAX_BYTES) file.delete()
            else used += file.length()
        }
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
}
