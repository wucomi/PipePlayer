package com.hik.pipeplayer.local

/**
 * 分片配置
 *
 * 存储在index.json中，记录视频的分片信息
 */
data class IndexConfig(
    val url: String,
    val totalSize: Long,
    val moovOffset: Long = 0,
    val moovSize: Long = 0,
    val mdatOffset: Long = 0,
    val mdatSize: Long = 0,
    val segments: MutableList<DataSegment> = mutableListOf()
)

/**
 * 文件头分片信息
 *
 * @property id 分片ID
 * @property startOffset 分片开始偏移量
 * @property endOffset 分片结束偏移量
 * @property state 状态：0未下载完成，1下载完成
 */
data class HeaderSegment(
    override val id: String,
    override val startOffset: Long,
    override val endOffset: Long,
    override var state: Int = 0,
) : Segment

/**
 * 数据分片信息
 *
 * @property id 分片ID
 * @property startOffset 分片开始偏移量
 * @property endOffset 分片结束偏移量
 * @property startTimeMs 分片开始时间
 * @property endTimeMs 分片结束时间
 * @property state 状态：0未下载完成，1下载完成
 */
data class DataSegment(
    override val id: String,
    override val startOffset: Long,
    override val endOffset: Long,
    val startTimeMs: Long,
    val endTimeMs: Long,
    override var state: Int = 0,
) : Segment

/**
 * 分片接口
 */
interface Segment {
    val id: String
    val startOffset: Long
    val endOffset: Long
    var state: Int
}