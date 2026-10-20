//! 评测服务实际返回的词级信息，缺失分数用 null 表示。
package org.qingjian.speech.learning.assessment

data class WordResult(val text: String, val score: Double?, val issue: String, val phones: List<String>)
