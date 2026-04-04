package com.hik.pipeplayer.analyzer

import androidx.annotation.Keep

/**
 * MP4结构信息
 *
 * 存储MP4文件的关键box位置和大小信息
 */
@Keep
data class Mp4StructureInfo(
    val ftypSize: Long,
    val moovOffset: Long,
    val moovSize: Long,
    val mdatOffset: Long,
    val mdatSize: Long
)

/**
 * 分片范围信息
 *
 * @param startTimeMs 开始时间（毫秒）
 * @param endTimeMs 结束时间（毫秒）
 * @param startOffset 开始字节偏移量
 * @param endOffset 结束字节偏移量
 */
@Keep
data class SegmentRange(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val startOffset: Long,
    val endOffset: Long
)
