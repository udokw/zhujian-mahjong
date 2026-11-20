package mahjong.app.update

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * 极简 ContentProvider：只为了把下载好的 APK 以 content:// 形式交给系统安装器。
 * Android 7.0 起不允许直接传 file:// 给其它应用（FileUriExposedException）。
 */
class ApkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val context = context ?: error("no context")
        val name = uri.lastPathSegment ?: error("missing file name")
        // 安装包先落在暂存目录；9 及以下可能直接躺在应用专属下载目录里。
        val file = listOf(stagingDir(context), downloadDir(context))
            .map { File(it, name) }
            .firstOrNull { it.isFile }
            ?: error("missing apk: $name")
        // 只允许读这两个目录内的文件，挡住 ../ 之类越界路径。
        if (!file.canonicalPath.startsWith(stagingDir(context).canonicalPath) &&
            !file.canonicalPath.startsWith(downloadDir(context).canonicalPath)
        ) {
            error("outside update dirs")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        fun uriFor(context: Context, file: File): Uri =
            Uri.parse("content://${context.packageName}.update/${file.name}")
    }
}
