package zone.disinfo.wx

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Uses AGP's pre-uninstall collection; direct adb runs retain the private-directory fallback. */
internal fun deviceArtifactDirectory(context: Context): File {
    val configured = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
    val directory =
        configured?.takeIf { it.isNotBlank() }?.let(::File) ?: File(context.filesDir, "e2e")
    check(directory.isDirectory || directory.mkdirs()) {
        "Cannot create device artifact directory: $directory"
    }
    return directory
}
