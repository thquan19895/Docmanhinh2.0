package com.example.screenwatcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Sao lưu bộ luật ra một thư mục riêng do người dùng chọn (Storage Access Framework).
 * Thư mục này nằm ngoài vùng dữ liệu của app nên cài lại / xoá dữ liệu app vẫn còn.
 * Mỗi bộ luật là một file "<tên>.sw.zip" chứa profile.json + ảnh mẫu (tpl_*.png) + ảnh gốc (base_*.bin).
 * Khi mở app: tự nạp lại những bộ luật còn thiếu từ thư mục này.
 */
object ProfileBackup {
    private const val KEY_TREE = "backupTree"
    private const val KEY_LABEL = "backupLabel"
    private const val SUFFIX = ".sw.zip"
    private const val PROFILE_JSON = "profile.json"

    private fun prefs(c: Context) = c.getSharedPreferences("sw", Context.MODE_PRIVATE)

    fun treeUri(c: Context): Uri? = prefs(c).getString(KEY_TREE, null)?.let { Uri.parse(it) }
    fun label(c: Context): String = prefs(c).getString(KEY_LABEL, "") ?: ""

    /** Còn quyền ghi vào thư mục đã chọn không (bị thu hồi khi xoá thư mục / gỡ quyền). */
    fun hasAccess(c: Context): Boolean {
        val t = treeUri(c) ?: return false
        return c.contentResolver.persistedUriPermissions.any { it.uri == t && it.isReadPermission && it.isWritePermission }
    }

    fun setTree(c: Context, uri: Uri) {
        runCatching {
            c.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val name = runCatching {
            c.contentResolver.query(
                docUri(uri, DocumentsContract.getTreeDocumentId(uri)),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        prefs(c).edit().putString(KEY_TREE, uri.toString()).putString(KEY_LABEL, name).apply()
    }

    fun clear(c: Context) {
        treeUri(c)?.let { t ->
            runCatching {
                c.contentResolver.releasePersistableUriPermission(
                    t, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        prefs(c).edit().remove(KEY_TREE).remove(KEY_LABEL).apply()
    }

    /* ---------------- thao tác với thư mục (chạy trong luồng nền) ---------------- */

    private fun docUri(tree: Uri, docId: String) = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    private class Child(val id: String, val name: String)

    private fun children(c: Context, tree: Uri): List<Child> {
        val parent = DocumentsContract.getTreeDocumentId(tree)
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val out = ArrayList<Child>()
        c.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { cur -> while (cur.moveToNext()) out.add(Child(cur.getString(0), cur.getString(1) ?: "")) }
        return out
    }

    private fun safeName(name: String) =
        name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "Bộ luật" } + SUFFIX

    private fun zipDir(dir: File): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for (f in dir.listFiles() ?: emptyArray()) {
                val n = f.name
                if (n != PROFILE_JSON && !(n.startsWith("tpl_") && n.endsWith(".png")) && !(n.startsWith("base_") && n.endsWith(".bin"))) continue
                z.putNextEntry(ZipEntry(n))
                z.write(f.readBytes())
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** Ghi (đè) bộ luật [id] ra thư mục sao lưu. Trả true nếu thành công. */
    fun export(c: Context, id: String): Boolean {
        return runCatching { exportOrThrow(c, id) }
            .getOrElse { AppState.log("Sao lưu bộ luật lỗi: ${it.message}"); false }
    }

    private fun exportOrThrow(c: Context, id: String): Boolean {
        val tree = treeUri(c) ?: return false
        if (!hasAccess(c)) return false
        val dir = ProfileStore.dirOf(c, id)
        val name = JSONObject(File(dir, PROFILE_JSON).readText()).optString("name", "Bộ luật")
        val fileName = safeName(name)
        children(c, tree).filter { it.name == fileName }.forEach {
            runCatching { DocumentsContract.deleteDocument(c.contentResolver, docUri(tree, it.id)) }
        }
        val created = DocumentsContract.createDocument(
            c.contentResolver, docUri(tree, DocumentsContract.getTreeDocumentId(tree)), "application/zip", fileName,
        ) ?: return false
        c.contentResolver.openOutputStream(created, "w")?.use { it.write(zipDir(dir)) } ?: return false
        return true
    }

    fun exportAll(c: Context): Int = ProfileStore.list(c).count { export(c, it.id) }

    /** Xoá file sao lưu của bộ luật tên [name] (khi xoá / đổi tên bộ luật). */
    fun deleteByName(c: Context, name: String) {
        runCatching {
            val tree = treeUri(c) ?: return
            if (!hasAccess(c)) return
            val fileName = safeName(name)
            children(c, tree).filter { it.name == fileName }.forEach {
                DocumentsContract.deleteDocument(c.contentResolver, docUri(tree, it.id))
            }
        }
    }

    /** Chạy [block] trong luồng nền — dùng cho các thao tác ghi file sau khi lưu / đổi tên / xoá bộ luật. */
    fun async(block: () -> Unit) { Thread { runCatching(block) }.start() }

    /**
     * Nạp các bộ luật trong thư mục sao lưu vào app. [overwrite] = false: bỏ qua bộ luật đã có cùng tên trong app.
     * Trả số bộ luật đã nạp.
     */
    fun importAll(c: Context, overwrite: Boolean): Int {
        val tree = treeUri(c) ?: return 0
        if (!hasAccess(c)) return 0
        var n = 0
        val kids = runCatching { children(c, tree) }.getOrDefault(emptyList())
        for (ch in kids) {
            if (!ch.name.endsWith(SUFFIX)) continue
            runCatching {
                val bytes = c.contentResolver.openInputStream(docUri(tree, ch.id))?.use { it.readBytes() } ?: return@runCatching
                val files = LinkedHashMap<String, ByteArray>()
                ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val nm = File(e.name).name // chỉ lấy tên file, tránh đường dẫn lạ trong zip
                        val ok = nm == PROFILE_JSON || (nm.startsWith("tpl_") && nm.endsWith(".png")) || (nm.startsWith("base_") && nm.endsWith(".bin"))
                        if (ok) files[nm] = z.readBytes()
                    }
                }
                val json = JSONObject(String(files[PROFILE_JSON] ?: return@runCatching, Charsets.UTF_8))
                val name = json.optString("name", "Bộ luật")
                val existing = ProfileStore.findByName(c, name)
                if (existing != null && !overwrite) return@runCatching
                val id = existing?.id ?: UUID.randomUUID().toString()
                val d = ProfileStore.dirOf(c, id)
                d.deleteRecursively(); d.mkdirs()
                for ((fn, data) in files) File(d, fn).writeBytes(data)
                n++
            }
        }
        return n
    }
}
