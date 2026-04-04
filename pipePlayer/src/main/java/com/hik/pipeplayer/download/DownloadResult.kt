package com.hik.pipeplayer.download

import java.io.File

/**
 * 下载结果
 *
 * @param success 是否成功
 * @param start 实际下载的起始字节位置
 * @param end 实际下载的结束字节位置
 * @param totalSize 文件总大小
 * @param file 下载文件
 */
data class DownloadResult(
    val success: Boolean,
    val start: Long,
    val end: Long,
    val totalSize: Long,
    val file: File
)