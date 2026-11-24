package sh.belfry.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/** Loads a file from the repo's vectors/ directory (the Gradle test task passes its path). */
object Vectors {
    private val dir: File by lazy {
        val p = System.getProperty("vectors.dir")
        if (p != null) File(p) else File("../vectors")
    }

    /** The file itself (to pin its sha256). */
    fun file(name: String): File = File(dir, name)

    fun load(name: String): JsonObject = Json.parseToJsonElement(File(dir, name).readText(Charsets.UTF_8)).jsonObject
}
