//! 离线测验按能力分别记录，不将笔记辨认与拼写混为掌握程度。
package org.qingjian.speech.learning.quiz

enum class Kind(val label: String) {
    CHOICE("词义选择"),
    SPELLING("中文拼写"),
}
