package com.naury.framekit.android.session

import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Files a project refers to by id, such as background-removal masks.
 *
 * The editor keeps assets inside its session folder so they survive process death and are deleted
 * with the session; headless processing uses a private cache folder. Ids are generated here and
 * checked on lookup, so a project can never point outside [directory].
 */
public class ProjectAssetStore(public val directory: File) {

    /**
     * Writes a new asset and returns its id.
     *
     * @throws FrameKitException with `OUTPUT_WRITE_FAILED` when the file cannot be written.
     */
    public fun write(extension: String, writer: (OutputStream) -> Unit): String {
        require(extension.matches(Regex("[a-z0-9]{1,8}"))) { "Invalid extension" }
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw FrameKitException(EditorErrorCode.OUTPUT_WRITE_FAILED, "Could not create asset directory")
        }
        val id = "${UUID.randomUUID()}.$extension"
        val temp = File(directory, "$id.tmp")
        try {
            temp.outputStream().buffered().use(writer)
            if (!temp.renameTo(File(directory, id))) throw IOException("Could not publish asset")
        } catch (error: IOException) {
            temp.delete()
            throw FrameKitException(EditorErrorCode.OUTPUT_WRITE_FAILED, "Asset could not be written", error)
        }
        return id
    }

    /** File of an asset, or `null` when the id is malformed or the file is missing. */
    public fun file(id: String): File? {
        if (!id.matches(ID_PATTERN)) return null
        return File(directory, id).takeIf(File::isFile)
    }

    /** Deletes every asset in [directory]. */
    public fun clear() {
        directory.listFiles()?.forEach(File::delete)
    }

    private companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}\\.[a-z0-9]{1,8}")
    }
}
