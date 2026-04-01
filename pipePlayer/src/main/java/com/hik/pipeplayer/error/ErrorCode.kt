package com.hik.pipeplayer.error

/**
 * 错误码统一维护
 *
 * 所有模块的错误码都在这里定义，避免重复和冲突
 */
object ErrorCode {

    // 网络相关错误 (1000-1099)
    const val NETWORK_ERROR = 1001
    const val NETWORK_TIMEOUT = 1002
    const val NETWORK_IO_ERROR = 1003
    const val NETWORK_RANGE_NOT_SUPPORTED = 1004

    // 存储相关错误 (1100-1199)
    const val STORAGE_ERROR = 1101
    const val STORAGE_NO_SPACE = 1102
    const val STORAGE_WRITE_ERROR = 1103
    const val STORAGE_READ_ERROR = 1104

    // 解析相关错误 (1200-1299)
    const val PARSE_ERROR = 1201
    const val PARSE_MP4_ERROR = 1202
    const val PARSE_INDEX_ERROR = 1203

    // 播放相关错误 (1300-1399)
    const val PLAYBACK_ERROR = 1301
    const val PLAYBACK_CODEC_ERROR = 1302

    // 下载相关错误 (1400-1499)
    const val DOWNLOAD_ERROR = 1401
    const val DOWNLOAD_CANCELED = 1402
    const val DOWNLOAD_TASK_NOT_FOUND = 1403

    // 缓存相关错误 (1500-1599)
    const val CACHE_ERROR = 1501
    const val CACHE_DISK_LIMIT_EXCEEDED = 1502
    const val CACHE_NOT_FOUND = 1503

    // 磁盘空间错误 (1600-1699)
    const val DISK_FULL = 1601

    // 分片相关错误 (1700-1799)
    const val NO_SEGMENTS = 1701

    // 未知错误
    const val UNKNOWN = 2001

    /**
     * 获取错误码对应的描述
     */
    fun getDescription(code: Int): String {
        return when (code) {
            NETWORK_ERROR -> "网络错误"
            NETWORK_TIMEOUT -> "网络超时"
            NETWORK_IO_ERROR -> "网络IO错误"
            NETWORK_RANGE_NOT_SUPPORTED -> "服务器不支持Range请求"

            STORAGE_ERROR -> "存储错误"
            STORAGE_NO_SPACE -> "存储空间不足"
            STORAGE_WRITE_ERROR -> "文件写入错误"
            STORAGE_READ_ERROR -> "文件读取错误"

            PARSE_ERROR -> "解析错误"
            PARSE_MP4_ERROR -> "MP4解析错误"
            PARSE_INDEX_ERROR -> "索引解析错误"

            PLAYBACK_ERROR -> "播放错误"
            PLAYBACK_CODEC_ERROR -> "解码错误"

            DOWNLOAD_ERROR -> "下载错误"
            DOWNLOAD_CANCELED -> "下载已取消"
            DOWNLOAD_TASK_NOT_FOUND -> "下载任务不存在"

            CACHE_ERROR -> "缓存错误"
            CACHE_DISK_LIMIT_EXCEEDED -> "超出磁盘限制"
            CACHE_NOT_FOUND -> "缓存不存在"

            DISK_FULL -> "磁盘空间不足"

            NO_SEGMENTS -> "没有分片"

            else -> "未知错误"
        }
    }
}
