package com.cressu.screendock.logs

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Writes log reports to the app cache and exposes them via the `.logs` FileProvider (see res/xml/file_paths.xml). */
object LogFiles {
    private const val DIR = "logs"
    private const val FILE = "screendock-logs.txt"

    /** Throws on I/O failure; callers decide how to fall back. */
    fun write(context: Context, text: String): Uri {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val file = File(dir, FILE).apply { writeText(text) }
        return FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
    }
}
