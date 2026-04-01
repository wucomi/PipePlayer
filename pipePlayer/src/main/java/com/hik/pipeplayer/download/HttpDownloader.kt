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
    ): Boolean = withContext(Dispatchers.IO) {
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
                    return@withContext false
                }

                response.body?.byteStream()?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "下载失败: ${e.message}")
            false
        }
    }

    override fun release() {
        client.dispatcher.cancelAll()
    }
}
