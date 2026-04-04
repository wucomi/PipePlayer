package com.hik.pipeplayer.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class DownloadManager() {
    private var downloader: IDownloader = HttpDownloader()
    private val tasks = CopyOnWriteArrayList<Task>()
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    fun setDownloader(downloader: IDownloader) {
        this.downloader = downloader
    }

    suspend fun checkRangeSupport(url: String): IDownloader.RangeResult {
        return downloader.checkRangeSupport(url)
    }

    suspend fun download(
        url: String,
        segmentId: String?,
        start: Long,
        end: Long,
        file: File
    ): CompletableDeferred<DownloadResult> {
        val completable = CompletableDeferred<DownloadResult>()
        val job = coroutineScope.launch(start = CoroutineStart.LAZY) {
            val result = downloader.download(url, start, end, file)
            completable.complete(result)
        }
        tasks.add(Task(url, segmentId, start, end, file, job))
        if (tasks.size == 1) {
            while (tasks.isNotEmpty()) {
                val task = tasks.first()
                val job = task.job
                job.start()
                job.join()
                tasks.remove(task)
            }
        }
        return completable
    }

    fun isDownloading(videoUrl: String, id: String): Boolean {
        return tasks.indexOfFirst { it.url == videoUrl && it.segmentId == id } != -1
    }

    fun cancel(url: String, segmentId: String) {
        tasks.removeIf {
            val remove = it.url == url && (it.segmentId == segmentId)
            if (remove) {
                it.job.cancel()
            }
            remove
        }
    }

    fun cancel(url: String, excludeSegmentIds: List<String> = emptyList()) {
        tasks.removeIf {
            val remove = it.url == url && !excludeSegmentIds.contains(it.segmentId)
            if (remove) {
                it.job.cancel()
            }
            remove
        }
    }

    fun release() {
        downloader.release()
    }
}

internal data class Task(
    val url: String,
    val segmentId: String?,
    val start: Long,
    val end: Long,
    val file: File,
    val job: Job,
)