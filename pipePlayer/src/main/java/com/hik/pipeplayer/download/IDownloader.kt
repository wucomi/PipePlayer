package com.hik.pipeplayer.download

import java.io.File

/**
 * 下载器接口
 *
 * 提供最简单的下载能力
 */
interface IDownloader {

    /**
     * Range检测结果
     */
    data class RangeResult(
        val supportsRange: Boolean,
        val totalSize: Long
    )

    /**
     * 检测是否支持Range请求
     *
     * @param url 资源URL
     * @return Range检测结果
     */
    suspend fun checkRangeSupport(url: String): RangeResult

    /**
     * 下载指定字节范围到文件
     *
     * @param url 资源URL
     * @param start 起始字节位置（-1表示从0开始下载完整文件）
     * @param end 结束字节位置（-1表示下载到文件结束）
     * @param file 目标文件
     * @return 是否成功
     */
    suspend fun download(url: String, start: Long, end: Long, file: File): Boolean

    /**
     * 释放资源
     */
    fun release()
}
