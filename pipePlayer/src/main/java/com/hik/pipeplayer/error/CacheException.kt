package com.hik.pipeplayer.error

/**
 * 缓存相关异常
 * 只使用错误码，错误信息通过 ErrorCode.getDescription 获取
 */
open class CacheException(val errorCode: Int) : Exception(ErrorCode.getDescription(errorCode))

/**
 * 磁盘空间不足异常
 */
class DiskSpaceException : CacheException(ErrorCode.DISK_FULL)

/**
 * MP4解析异常
 */
class Mp4ParseException : CacheException(ErrorCode.PARSE_MP4_ERROR)

/**
 * 下载异常
 */
class DownloadException : CacheException(ErrorCode.DOWNLOAD_ERROR)
