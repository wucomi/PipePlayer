package com.hik.pipeplayer.cache

import android.util.Log
import com.hik.pipeplayer.local.DataSegment
import com.hik.pipeplayer.local.HeaderSegment
import com.hik.pipeplayer.local.IndexConfig
import com.hik.pipeplayer.local.LocalCache
import com.hik.pipeplayer.local.Segment
import com.hik.pipeplayer.download.DownloadManager
import com.hik.pipeplayer.download.IDownloader
import com.hik.pipeplayer.error.CacheException
import com.hik.pipeplayer.error.DiskSpaceException
import com.hik.pipeplayer.error.DownloadException
import com.hik.pipeplayer.error.ErrorCode
import com.hik.pipeplayer.analyzer.Mp4Analyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * 流媒体加载器
 *
 * 核心调度模块，协调缓存流程
 *
 * 播放流程：
 * 1. 用户点击播放 -> 调用StreamingLoader.start()
 * 2. StreamingLoader回调onReady -> 开始播放
 * 3. 播放过程中实时同步进度 -> StreamingLoader.seek()
 * 4. 缓存不足回调onBufferingLack -> 暂停播放，显示加载
 * 5. 缓冲完成回调onBufferingReady -> 恢复播放
 * 6. 全部完成回调onComplete -> 显示完成
 *
 * 主要职责：
 * 1. 检测MP4结构，解析moov元数据
 * 2. 生成分片配置（按时长切割）
 * 3. 下载分片
 * 4. 监控缓存状态，触发回调
 *
 * @param cachePath 缓存路径
 * @param videoUrl 视频URL（每个StreamingLoader实例只管理一个视频）
 */
class StreamLoader(
    cachePath: String,
    private val videoUrl: String
) {

    companion object {
        private const val TAG = "StreamingLoader"
        private const val DEFAULT_SEGMENT_DURATION_MS = 5000L
        private const val DEFAULT_PRELOAD_SEGMENT_COUNT = 3
        private const val DEFAULT_DISK_LIMIT_BYTES = 500 * 1024 * 1024L
    }

    private val localCache = LocalCache(cachePath)
    private var indexConfig: IndexConfig? = null

    private var downloadManager: DownloadManager = DownloadManager()
    var callback: CacheCallback? = null

    private var segmentDurationMs = DEFAULT_SEGMENT_DURATION_MS
    private var preloadSegmentCount = DEFAULT_PRELOAD_SEGMENT_COUNT
    private var diskLimitBytes: Long = DEFAULT_DISK_LIMIT_BYTES
    private var currentTimeMs: Long = 0
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    /**
     * 设置分片时长
     */
    fun setSegmentTime(durationMs: Long) {
        segmentDurationMs = durationMs
    }

    /**
     * 设置预加载分片数
     */
    fun setPreloadSegmentCount(count: Int) {
        preloadSegmentCount = count
    }

    /**
     * 设置磁盘缓存限制
     */
    fun setDiskLimit(limitBytes: Long) {
        diskLimitBytes = limitBytes
    }

    /**
     * 设置下载引擎
     */
    fun setDownloader(downloader: IDownloader) {
        downloadManager.setDownloader(downloader)
    }

    /**
     * 启动缓存
     */
    fun start() = coroutineScope.launch(Dispatchers.IO) {
        Log.d(TAG, "启动缓存: $videoUrl")

        try {
            // 尝试加载index配置
            indexConfig = localCache.getIndexConfig(videoUrl)

            // 配置不存在，检测MP4结构、生成分片配置
            if (indexConfig == null) {
                Log.d(TAG, "配置不存在，检查是否支持分段下载")
                val rangeResult = downloadManager.checkRangeSupport(videoUrl)
                if (rangeResult.supportsRange) {
                    Log.d(TAG, "支持分段下载，开始生成配置文件")
                    generateIndexConfig(videoUrl, rangeResult.totalSize)
                }
            }

            // 判断是否生成了IndexConfig
            if (indexConfig == null) {
                Log.d(TAG, "不支持分段下载，下载完整视频")
                val filePath = downloadComplete()
                if (filePath != null) {
                    Log.d(TAG, "完整视频下载完成")
                    withContext(Dispatchers.Main) {
                        callback?.onReady(filePath)
                        callback?.onComplete(filePath)
                    }
                } else {
                    Log.d(TAG, "完整视频下载失败")
                    callOnError(videoUrl, ErrorCode.DOWNLOAD_ERROR)
                }
            } else {
                val playFile = localCache.getPlayFile(videoUrl)
                if (playFile.exists()) {
                    Log.d(TAG, "有播放文件，执行onReady回调")
                    withContext(Dispatchers.Main) {
                        callback?.onReady(playFile.absolutePath)
                    }
                    seekTo(0)
                } else {
                    val firstSegment = indexConfig?.segments?.firstOrNull()
                    if (firstSegment == null) {
                        Log.e(TAG, "没有分片配置")
                        callOnError(videoUrl, ErrorCode.NO_SEGMENTS)
                        return@launch
                    }

                    Log.d(TAG, "没有播放文件，下载第一个分片生成播放文件")
                    val segmentTempPath = downloadSegment(firstSegment)
                    if (segmentTempPath == null) {
                        Log.d(TAG, "第一个分片下载失败")
                        callOnError(videoUrl, ErrorCode.DOWNLOAD_ERROR)
                    } else {
                        // 更新状态
                        firstSegment.state = 1
                        indexConfig?.let { config ->
                            localCache.saveIndexConfig(videoUrl, config)
                        }

                        val segmentTempFile = File(segmentTempPath)

                        // 创建稀疏文件：分配完整视频大小，确保播放器可以正确解析
                        val totalSize = indexConfig?.totalSize ?: segmentTempFile.length()
                        localCache.createPlayFile(videoUrl, totalSize)

                        // 将第一个分片写入到正确的位置
                        val playFile = localCache.getPlayFile(videoUrl)
                        RandomAccessFile(playFile, "rw").use { raf ->
                            raf.seek(firstSegment.startOffset)
                            segmentTempFile.inputStream().use { input ->
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    raf.write(buffer, 0, bytesRead)
                                }
                            }
                        }

                        // 清理临时文件
                        segmentTempFile.delete()
                        Log.d(TAG, "生成播放文件，执行onReady回调")
                        withContext(Dispatchers.Main) {
                            callback?.onReady(playFile.absolutePath)
                        }
                        seekTo(0)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "缓存初始化失败: ${e.message}")
            withContext(Dispatchers.Main) {
                val cacheException = e as? CacheException
                callOnError(videoUrl, cacheException?.errorCode ?: ErrorCode.UNKNOWN)
            }
        }
    }

    /**
     * 同步播放进度
     * 根据当前进度找到需要预加载的分片
     */
    fun seekTo(timeMs: Long) = coroutineScope.launch(Dispatchers.IO) {
        currentTimeMs = timeMs
        Log.d(TAG, "同步进度: currentTimeMs=$timeMs")
        try {
            // 更新缓存状态
            updateBufferStatus()
            // 预加载分片
            val activeSegments = getActiveSegments()
            // 取消非窗口内的缓冲请求
            downloadManager.cancel(videoUrl, activeSegments.map { it.id })
            val needPreloadSegments = activeSegments.filter {
                it.state == 0 && !downloadManager.isDownloading(videoUrl, it.id)
            }
            if (needPreloadSegments.isNotEmpty()) {
                Log.d(TAG, "需要下载分片: segments=${needPreloadSegments.map { it.id }}")
                // 串行下载分片
                needPreloadSegments.forEach { segment ->
                    downloadDataSegment(segment)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "缓存失败: ${e.message}")
            withContext(Dispatchers.Main) {
                val cacheException = e as? CacheException
                callOnError(videoUrl, cacheException?.errorCode ?: ErrorCode.CACHE_ERROR)
            }
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        coroutineScope.cancel()
        downloadManager.release()
    }

    private suspend fun generateIndexConfig(videoUrl: String, totalSize: Long) {
        val headerTempPath = downloadHeader() ?: throw DownloadException()
        val structureInfo = Mp4Analyzer.analyze(File(headerTempPath))
        val segmentRanges = Mp4Analyzer.getSegmentRanges(
            File(headerTempPath),
            DEFAULT_SEGMENT_DURATION_MS
        )
        File(headerTempPath).delete()
        indexConfig = IndexConfig(
            url = videoUrl,
            totalSize = totalSize,
            moovOffset = structureInfo.moovOffset,
            moovSize = structureInfo.moovSize,
            mdatOffset = structureInfo.mdatOffset,
            mdatSize = structureInfo.mdatSize,
            segments = segmentRanges.mapIndexed { index, range ->
                DataSegment(
                    getSegmentId(index),
                    range.startOffset,
                    range.endOffset,
                    range.startTimeMs,
                    range.endTimeMs,
                    0
                )
            }.toMutableList()
        )
        localCache.saveIndexConfig(videoUrl, indexConfig!!)
        Log.d(TAG, "generateCacheConfig完成")
    }

    @Throws
    private suspend fun downloadHeader(): String? {
        val initialSize = 1024 * 1024L
        // 下载前1MB
        val headerTempPath = downloadSegment(
            HeaderSegment(
                id = getHeaderSegmentId(0),
                startOffset = 0,
                endOffset = initialSize - 1,
            )
        )

        if (headerTempPath == null) return null

        // 分析是否包含完整moov
        val structureInfo = Mp4Analyzer.analyze(File(headerTempPath))
        if (structureInfo.mdatOffset > initialSize) {
            // 不完整,需要继续下载文件头
            val headerTempPath1 = downloadSegment(
                HeaderSegment(
                    id = getHeaderSegmentId(1),
                    startOffset = initialSize,
                    endOffset = structureInfo.mdatOffset,
                )
            )
            if (headerTempPath1 == null) return null
            File(headerTempPath1).copyTo(File(headerTempPath))
        }

        return headerTempPath
    }

    // 获取活跃窗口内的分片
    private fun getActiveSegments(): List<DataSegment> {
        val config = indexConfig ?: return emptyList()
        val segments = mutableListOf<DataSegment>()

        val currentSegmentIndex = config.segments.indexOfFirst {
            currentTimeMs in it.startTimeMs until it.endTimeMs
        }

        if (currentSegmentIndex == -1) return emptyList()

        val endIndex = minOf(
            currentSegmentIndex + 1 + preloadSegmentCount,
            config.segments.size
        )
        for (i in currentSegmentIndex until endIndex) {
            val segment = config.segments[i]
            segments.add(segment)
        }

        return segments
    }

    /**
     * 下载单个分片
     */
    private suspend fun downloadDataSegment(segment: DataSegment) {
        Log.d(TAG, "开始下载${segment.id}")
        try {
            val tempPath = downloadSegment(segment)
            if (tempPath != null) {
                // 合并到播放文件
                val playFile = localCache.getPlayFile(videoUrl)
                RandomAccessFile(playFile, "rw").use { raf ->
                    raf.seek(segment.startOffset)
                    File(tempPath).inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            raf.write(buffer, 0, bytesRead)
                        }
                    }
                }
                // 清理临时文件
                File(tempPath).delete()
                // 更新状态
                segment.state = 1
                indexConfig?.let { config ->
                    localCache.saveIndexConfig(videoUrl, config)
                }
                updateBufferStatus()
                Log.d(TAG, "分片${segment.id}下载完成")
            } else {
                Log.e(TAG, "分片${segment.id}下载失败")
                callOnError(videoUrl, ErrorCode.DOWNLOAD_ERROR)
            }
        } catch (e: Exception) {
            Log.e(TAG, "分片${segment.id}下载异常: ${e.message}")
            callOnError(videoUrl, ErrorCode.DOWNLOAD_ERROR)
        }
    }

    private suspend fun updateBufferStatus() {
        val segments = indexConfig?.segments ?: return
        val currentSegmentIndex = segments.indexOfFirst {
            currentTimeMs in it.startTimeMs until it.endTimeMs
        }.takeIf { it != -1 } ?: return
        val segment = segments[currentSegmentIndex]

        val bufferingLack = suspend {
            withContext(Dispatchers.Main) {
                callback?.onBufferingLack()
            }
        }

        val bufferReady = suspend {
            withContext(Dispatchers.Main) {
                callback?.onBufferingReady()
            }
        }

        if (segment.state == 0) {
            bufferingLack()
        } else if (currentSegmentIndex == segments.size - 1) {
            // 最后一个分片单独处理，后面没有预加载了
            bufferReady()
        } else {
            var cacheTimeMs = segment.endTimeMs - currentTimeMs
            if (cacheTimeMs < segmentDurationMs / 2 && currentSegmentIndex < segments.size - 1) {
                val nextSegment = segments[currentSegmentIndex + 1]
                if (nextSegment.state == 1) {
                    cacheTimeMs = nextSegment.endTimeMs - currentTimeMs
                }
            }
            Log.d(TAG, "cacheTimeMs: $cacheTimeMs")
            if (cacheTimeMs < segmentDurationMs / 2) {
                bufferingLack()
            } else {
                bufferReady()
            }
        }
    }

    private suspend fun downloadSegment(segment: Segment): String? {
        requireLimitDiskCache()
        val tempFile = localCache.getSegmentTempFile(videoUrl, segment.id)
        val success = downloadManager.download(
            url = videoUrl,
            segmentId = segment.id,
            start = segment.startOffset,
            end = segment.endOffset,
            file = tempFile
        ).await()
        return if (success) tempFile.absolutePath else null
    }

    private suspend fun downloadComplete(): String? {
        requireLimitDiskCache()
        val playFile = localCache.getPlayFile(videoUrl)
        val success = downloadManager.download(
            url = videoUrl,
            segmentId = null,
            start = -1,
            end = -1,
            file = playFile
        ).await()
        return if (success) playFile.absolutePath else null
    }

    private fun requireLimitDiskCache() {
        if (diskLimitBytes > 0) {
            if (!localCache.hasEnoughSpace(diskLimitBytes)) {
                throw DiskSpaceException()
            }
        }
    }

    private fun getHeaderSegmentId(index: Int) = "hed_${index.toString().padStart(5, '0')}"
    private fun getSegmentId(index: Int) = "seg_${index.toString().padStart(5, '0')}"
    private suspend fun callOnError(url: String, code: Int) = withContext(Dispatchers.Main) {
        callback?.onError(code)
    }
}