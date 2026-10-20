//! 仅连接讯飞固定主机的 WSS；系统信任链和主机名校验，无跳转或压缩扩展。
package org.qingjian.speech.learning.assessment.wire

import android.util.Base64
import org.qingjian.speech.learning.assessment.Signature
import java.net.InetSocketAddress
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class TlsChannel(private val signedPath: String): Channel {
    @Volatile private var socket: SSLSocket?=null
    @Volatile private var closed=false
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    override fun open() {
        require(signedPath.startsWith(Signature.PATH+"?") && signedPath.none {it=='\r'||it=='\n'})
        val connection=SSLSocketFactory.getDefault().createSocket() as SSLSocket
        socket=connection
        if(closed){connection.close();error("评测已取消")}
        connection.sslParameters=connection.sslParameters.apply {endpointIdentificationAlgorithm="HTTPS";serverNames=listOf(SNIHostName(Signature.HOST))}
        connection.connect(InetSocketAddress(Signature.HOST,443),15000);connection.soTimeout=25000;connection.startHandshake()
        input=connection.inputStream;output=connection.outputStream
        val nonce=Base64.encodeToString(ByteArray(16).also {SecureRandom().nextBytes(it)},Base64.NO_WRAP)
        output.write(("GET $signedPath HTTP/1.1\r\nHost: ${Signature.HOST}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $nonce\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(Charsets.US_ASCII));output.flush()
        val header=StringBuilder()
        while(!header.endsWith("\r\n\r\n")) {val c=input.read();check(c>=0 && header.length<16384);header.append(c.toChar())}
        val lines=header.toString().split("\r\n");val status=lines.first().split(' ').getOrNull(1)?.toIntOrNull()
        check(status==101) {when(status){401->"讯飞鉴权失败，请检查 APIKey 和 APISecret";403->"讯飞拒绝连接，请检查手机时间、权限或控制台限制";else->"讯飞连接失败（HTTP ${status ?: 0}）"}}
        val values=lines.drop(1).filter {':' in it}.associate {line->line.substringBefore(':').lowercase() to line.substringAfter(':').trim()}
        val expected=Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((nonce+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)),Base64.NO_WRAP)
        check(values["upgrade"].equals("websocket",true) && values["connection"].orEmpty().split(',').any {it.trim().equals("upgrade",true)} && values["sec-websocket-accept"]==expected) {"讯飞连接握手校验失败"}
        check(values["sec-websocket-extensions"].isNullOrBlank()) {"不支持此评测连接扩展"}
    }
    @Synchronized override fun send(text: String) {check(!closed);Frames.write(output,text.toByteArray(Charsets.UTF_8))}
    override fun receive()=Frames.read(input){bytes->synchronized(this){if(!closed)Frames.write(output,bytes,10)}}
    override fun close() {closed=true;runCatching {socket?.close()};socket=null}
}
