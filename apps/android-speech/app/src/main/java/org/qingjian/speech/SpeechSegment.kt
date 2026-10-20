//! 一段同语言朗读文本；中英文按脚本拆分，保持英文示范音色。
package org.qingjian.speech

data class SpeechSegment(val text:String,val chinese:Boolean)
