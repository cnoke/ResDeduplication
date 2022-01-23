package com.cnoke.resplugin

/**
 * @date on 2022/1/23
 * @author huanghui
 * @title
 * @describe
 */
open class ResDeduplicationConfig(
    var enable: Boolean = true
){
    var enableWhenDebug = true
    var ignoreFileName: Array<String>? = null
}