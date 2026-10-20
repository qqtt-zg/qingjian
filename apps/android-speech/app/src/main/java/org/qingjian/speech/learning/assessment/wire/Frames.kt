//! RFC6455 有界文本帧；客户端随机掩码、拒绝服务器掩码和超大/压缩帧。
package org.qingjian.speech.learning.assessment.wire

import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

object Frames {
    private val random=SecureRandom()
    fun write(output: OutputStream, bytes: ByteArray, opcode: Int=1) {
        require(bytes.size<=65535 && (opcode !in 8..10 || bytes.size<=125))
        output.write(0x80 or opcode)
        if(bytes.size<126)output.write(0x80 or bytes.size)
        else {output.write(0x80 or 126);output.write(bytes.size ushr 8);output.write(bytes.size and 255)}
        val mask=ByteArray(4).also {random.nextBytes(it)};output.write(mask)
        output.write(ByteArray(bytes.size) {i->(bytes[i].toInt() xor mask[i%4].toInt()).toByte()});output.flush()
    }
    fun read(input: InputStream, pong: (ByteArray)->Unit): String {
        val text=ByteArrayOutputStream();var started=false
        repeat(512) {
            val first=input.read();val second=input.read();check(first>=0 && second>=0) {"评测连接已关闭"}
            check(first and 0x70==0 && second and 0x80==0) {"评测帧格式不支持"}
            val fin=first and 0x80!=0;val opcode=first and 15
            var length=(second and 127).toLong()
            if(length==126L)length=(readByte(input)*256+readByte(input)).toLong()
            else if(length==127L) {length=0;repeat(8){check(length<=262144);length=length*256+readByte(input)}}
            check(length in 0..262144 && (opcode !in 8..10 || fin&&length<=125)) {"评测响应过大"}
            val bytes=ByteArray(length.toInt());var offset=0
            while(offset<bytes.size){val n=input.read(bytes,offset,bytes.size-offset);check(n>0);offset+=n}
            when(opcode) {
                8->error("评测连接已关闭，请重试")
                9->pong(bytes)
                10->Unit
                1,0->{check(if(opcode==1)!started else started);started=true;check(text.size()+bytes.size<=262144);text.write(bytes);if(fin)return text.toString("UTF-8")}
                else->error("评测响应类型不支持")
            }
        }
        error("评测响应分片过多")
    }
    private fun readByte(input: InputStream)=input.read().also {check(it>=0)}
}
