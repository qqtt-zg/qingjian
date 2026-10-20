//! 试听按钮的文本与可访问名称，便于局部界面刷新时释放绑定。
package org.qingjian.speech.learning.quiz

data class SpeechBinding(val button: android.widget.Button,val key:String,val text:String,val label:String)
