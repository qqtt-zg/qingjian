//! 本机跟读记录；自评与未来服务端评分分开，未评估时没有客观分数。
package org.qingjian.speech.learning.oral

data class Recording(
    val id: Long,

    val wordId: Long,

    val english: String,

    val filename: String,

    val duration: Long,

    val rating: String,
)
