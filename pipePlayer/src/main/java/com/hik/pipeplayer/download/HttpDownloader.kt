package com.hik.pipeplayer.download

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * HTTP下载器
 *
 * 最简单的HTTP下载实现
 */
class HttpDownloader : IDownloader {

    companion object {
        private const val TAG = "HttpDownloader"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun checkRangeSupport(url: String): IDownloader.RangeResult =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).head().build()
            client.newCall(request).execute().use { response ->
                val supportsRange = response.header("Accept-Ranges") == "bytes"
                val totalSize = response.header("Content-Length")?.toLongOrNull() ?: 0
                IDownloader.RangeResult(supportsRange, totalSize)
            }
        }

    override suspend fun download(
        url: String,
        start: Long,
        end: Long,
        file: File
    ): DownloadResult = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(url)

        // 设置Range头
        if (start >= 0 && end >= 0) {
            requestBuilder.header("Range", "bytes=$start-$end")
        } else if (start >= 0) {
            requestBuilder.header("Range", "bytes=$start-")
        }

        val request = requestBuilder.build()

        try {
            val call = client.newCall(request)
            coroutineContext.job.invokeOnCompletion { handler ->
                if (handler is CancellationException) {
                    call.cancel()
                }
            }
            call.execute().use { response ->
                if (response.code != 206 && response.code != 200) {
                    Log.e(TAG, "下载失败: HTTP ${response.code}")
                    return@withContext DownloadResult(false, 0, 0, 0, file)
                }

                // 解析Content-Range头获取实际下载范围和总大小
                val contentRange = response.header("Content-Range")
                val (actualStart, actualEnd, totalSize) = if (contentRange != null) {
                    // 格式: bytes start-end/totalSize
                    val rangePart = contentRange.removePrefix("bytes ")
                    val parts = rangePart.split("/")
                    if (parts.size == 2) {
                        val range = parts[0].split("-")
                        val s = range.getOrElse(0) { "0" }.toLongOrNull() ?: 0
                        val e = range.getOrElse(1) { "0" }.toLongOrNull() ?: 0
                        val total = parts[1].toLongOrNull() ?: 0
                        Triple(s, e, total)
                    } else {
                        Triple(0L, 0L, 0L)
                    }
                } else {
                    // 没有Content-Range，使用Content-Length
                    val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0
                    Triple(0L, contentLength - 1, contentLength)
                }

                response.body?.byteStream()?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                DownloadResult(true, actualStart, actualEnd, totalSize, file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "下载失败: ${e.message}")
            DownloadResult(false, 0, 0, 0, file)
        }
    }

    override fun release() {
        client.dispatcher.cancelAll()
    }
}
