package com.hik.pipeplayer.local

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地缓存管理
 *
 * 目录结构：
 * /video_cache/{video_hash}/
 * ├── index.json       # 分片配置
 * ├── video.mp4        # 播放文件（稀疏文件）
 * └── tmp/             # 下载中临时文件
 *     ├── meta_00000.tmp
 *     ├── seg_00000.tmp
 *     └── seg_00001.tmp
 */
class LocalCache(cachePath: String) {

    companion object {
        private const val TAG = "LocalCache"
        private const val CACHE_DIR = "video_cache"
        private const val INDEX_FILE = "index.json"
        private const val VIDEO_FILE = "video.mp4"
        private const val TMP_DIR = "tmp"
    }

    private val gson: Gson = GsonBuilder().create()

    private val cacheDir = File(cachePath, CACHE_DIR).apply {
        if (!exists()) mkdirs()
    }

    // 文件锁映射表
    private val fileLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * 获取文件锁
     */
    private fun getFileLock(key: String): Mutex {
        return fileLocks.getOrPut(key) { Mutex() }
    }

    /**
     * 获取磁盘剩余空间
     */
    fun getAvailableSpace(): Long {
        return cacheDir.freeSpace
    }

    /**
     * 检查磁盘空间是否足够
     */
    fun hasEnoughSpace(requiredSpace: Long): Boolean {
        return getAvailableSpace() >= requiredSpace
    }

    /**
     * 获取视频缓存目录
     */
    fun getCacheDir(videoUrl: String): File {
        val id = md5(videoUrl)
        return File(cacheDir, id).apply { mkdirs() }
    }

    /**
     * 获取临时文件目录
     */
    fun getTempDir(videoUrl: String): File {
        return File(getCacheDir(videoUrl), TMP_DIR).apply { mkdirs() }
    }

    /**
     * 获取分片临时文件路径
     */
    fun getSegmentTempFile(videoUrl: String, segmentId: String): File {
        val fileName = "${segmentId}.tmp"
        return File(getTempDir(videoUrl), fileName)
    }

    /**
     * 获取index.json文件路径
     */
    fun getIndexFile(videoUrl: String): File {
        return File(getCacheDir(videoUrl), INDEX_FILE)
    }

    /**
     * 获取video.mp4文件路径
     */
    fun getPlayFile(videoUrl: String): File {
        return File(getCacheDir(videoUrl), VIDEO_FILE)
    }

    /**
     * 获取Index配置
     */
    suspend fun getIndexConfig(videoUrl: String): IndexConfig? = withContext(Dispatchers.IO) {
        val file = getIndexFile(videoUrl)
        getFileLock(file.absolutePath).withLock {
            if (!file.exists()) return@withLock null
            try {
                gson.fromJson(file.readText(), IndexConfig::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "读取index.json失败: ${e.message}")
                null
            }
        }
    }

    /**
     * 保存索引配置
     */
    suspend fun saveIndexConfig(
        videoUrl: String,
        config: IndexConfig
    ): Boolean = withContext(Dispatchers.IO) {
        val file = getIndexFile(videoUrl)
        getFileLock(file.absolutePath).withLock {
            try {
                file.writeText(gson.toJson(config))
                true
            } catch (e: Exception) {
                Log.e(TAG, "保存index.json失败: ${e.message}")
                false
            }
        }
    }

    /**
     * 创建播放文件
     */
    suspend fun createPlayFile(
        videoUrl: String,
        totalSize: Long
    ): File = withContext(Dispatchers.IO) {
        val file = getPlayFile(videoUrl)
        RandomAccessFile(file, "rw").use { it.setLength(totalSize) }
        file
    }

    /**
     * 删除所有缓存配置
     */
    suspend fun delete(videoUrl: String) = withContext(Dispatchers.IO) {
        val dir = getCacheDir(videoUrl)
        dir.deleteRecursively()
    }

    private fun md5(url: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
