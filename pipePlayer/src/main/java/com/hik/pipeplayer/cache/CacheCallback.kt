package com.hik.pipeplayer.cache

import com.hik.pipeplayer.error.ErrorCode

/**
 * 缓存回调接口
 *
 * 定义缓存状态变化的回调方法
 */
interface CacheCallback {
    /**
     * 首分片就绪，可以开始播放
     *
     * @param filePath 缓存文件路径
     */
    fun onReady(filePath: String)

    /**
     * 缓存不足，需要缓冲
     *
     * 播放器应暂停播放，显示加载状态
     */
    fun onBufferingLack()

    /**
     * 所需分片就绪，缓冲完成
     */
    fun onBufferingReady()

    /**
     * 全缓存完成
     *
     * @param filePath 缓存文件路径
     */
    fun onComplete(filePath: String)

    /**
     * 缓存错误
     *
     * @param errorCode 错误码，参考 [ErrorCode]
     */
    fun onError(errorCode: Int)
}
