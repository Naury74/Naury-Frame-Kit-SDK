package com.naury.framekit.android.session

import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * 배경 제거 마스크처럼 프로젝트가 id로 참조하는 파일.
 *
 * 편집기는 asset을 세션 폴더 안에 두어 프로세스가 종료돼도 살아남고 세션과 함께 삭제되게 한다.
 * headless 처리는 전용 캐시 폴더를 사용한다. id는 여기서 생성하고 조회할 때 검사하므로
 * 프로젝트가 [directory] 바깥을 가리킬 수 없다.
 */
public class ProjectAssetStore(public val directory: File) {

    /**
     * 새 asset을 쓰고 그 id를 반환한다.
     *
     * @throws FrameKitException 파일을 쓸 수 없을 때 `OUTPUT_WRITE_FAILED` 코드로 던진다.
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

    /** asset 파일. id 형식이 잘못되었거나 파일이 없으면 `null`. */
    public fun file(id: String): File? {
        if (!id.matches(ID_PATTERN)) return null
        return File(directory, id).takeIf(File::isFile)
    }

    /** [directory]의 모든 asset을 삭제한다. */
    public fun clear() {
        directory.listFiles()?.forEach(File::delete)
    }

    private companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}\\.[a-z0-9]{1,8}")
    }
}
