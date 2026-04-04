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
    val mdatSize: Long,
)

/**
 * 分片范围信息
 *
 * @param startTimeMs 开始时间（毫秒）
 * @param endTimeMs 结束时间（毫秒）
 * @param startOffset 开始字节偏移量
 * @param endOffset 结束字节偏移量
 * @param firstSample 首帧索引
 * @param endSample 结束帧索引
 */
@Keep
data class SegmentRange(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val startOffset: Long,
    val endOffset: Long,
    val firstSample: Long,
    val endSample: Long,
)

/**
 * stts条目预处理信息
 *
 * @param firstTime 开始时间（毫秒）
 * @param firstSample 开始采样索引
 * @param count 采样数量
 * @param deltaMs 每个采样的时间（毫秒）
 */
@Keep
data class SttsEntryInfo(
    val firstTime: Long,
    val firstSample: Long,
    val count: Long,
    val deltaMs: Int,
)

/**
 * stsc条目预处理信息
 *
 * @param firstSample 开始采样索引
 * @param firstChunk 开始chunk索引
 * @param chunkCount chunk数量
 * @param samplesPerChunk 每个chunk的采样数
 */
@Keep
data class StscEntryInfo(
    val firstSample: Long,
    val firstChunk: Long,
    val chunkCount: Long,
    val samplesPerChunk: Long,
)
