package com.musiccut.app.data

import android.content.Context
import com.musiccut.core.separation.HtDemucsModelSpec
import com.musiccut.core.separation.MdxModelSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Downloads separation models once and keeps them in app-private storage for offline reuse. */
class ModelManager(context: Context) {
    private val dir = File(context.filesDir, "models").apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    private data class Asset(
        val fileName: String,
        val url: String,
        val approxSizeMb: Int,
        val sha256: String? = null,
    )

    private fun asset(spec: MdxModelSpec) = Asset(spec.fileName, spec.url, spec.approxSizeMb)
    private fun asset(spec: HtDemucsModelSpec) = Asset(spec.fileName, spec.url, spec.approxSizeMb, spec.sha256)
    private fun file(asset: Asset) = File(dir, asset.fileName)

    fun file(spec: MdxModelSpec) = file(asset(spec))
    fun file(spec: HtDemucsModelSpec) = file(asset(spec))

    private fun isInstalled(asset: Asset): Boolean {
        val f = file(asset)
        val sensibleMinimum = (asset.approxSizeMb * 1_048_576L * 0.65).toLong()
        return f.isFile && f.length() >= sensibleMinimum
    }

    fun isInstalled(spec: MdxModelSpec) = isInstalled(asset(spec))
    fun isInstalled(spec: HtDemucsModelSpec) = isInstalled(asset(spec))

    fun delete(spec: MdxModelSpec) = file(spec).delete()
    fun delete(spec: HtDemucsModelSpec) = file(spec).delete()

    fun download(spec: MdxModelSpec, progress: (Float) -> Unit, isCancelled: () -> Boolean): File =
        download(asset(spec), progress, isCancelled)

    fun download(spec: HtDemucsModelSpec, progress: (Float) -> Unit, isCancelled: () -> Boolean): File =
        download(asset(spec), progress, isCancelled)

    private fun download(asset: Asset, progress: (Float) -> Unit, isCancelled: () -> Boolean): File {
        val target = file(asset)
        if (isInstalled(asset)) return target
        if (target.exists()) target.delete()
        val tmp = File(dir, asset.fileName + ".part")
        tmp.delete()
        val digest = asset.sha256?.let { MessageDigest.getInstance("SHA-256") }
        var read = 0L
        try {
            val req = Request.Builder().url(asset.url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("모델 다운로드 실패: HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("빈 응답")
                val total = body.contentLength().takeIf { it > 0 } ?: (asset.approxSizeMb * 1_048_576L)
                body.byteStream().use { input ->
                    tmp.outputStream().buffered().use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            if (isCancelled()) throw InterruptedException("cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest?.update(buf, 0, n)
                            read += n
                            progress((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            val sensibleMinimum = (asset.approxSizeMb * 1_048_576L * 0.65).toLong()
            if (read < sensibleMinimum) throw IOException("모델 파일이 예상보다 작습니다 (${read / 1_048_576}MB)")
            asset.sha256?.let { expected ->
                val actual = digest!!.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw IOException("모델 SHA-256 검증 실패")
                }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            progress(1f)
            return target
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }
}
