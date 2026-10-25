//! 文本意图提取与安全防篡改单元测试：验证中文意图句型识别、维度绑定、词元清洗与权限最小化。
package org.qingjian.speech.learning.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextIntentExtractorTest {

    @Test
    fun testExtractStandardIntents() {
        val i1 = TextIntentExtractor.extract("我想练一下 deploy")
        assertNotNull(i1)
        assertEquals("deploy", i1?.english)

        val i2 = TextIntentExtractor.extract("我要复习 candidate")
        assertNotNull(i2)
        assertEquals("candidate", i2?.english)

        val i3 = TextIntentExtractor.extract("把 photosynthesis 加到待练")
        assertNotNull(i3)
        assertEquals("photosynthesis", i3?.english)

        val i4 = TextIntentExtractor.extract("重点练习 algorithm")
        assertNotNull(i4)
        assertEquals("algorithm", i4?.english)

        val i5 = TextIntentExtractor.extract("practice vocabulary")
        assertNotNull(i5)
        assertEquals("vocabulary", i5?.english)
    }

    @Test
    fun testExtractDimensions() {
        val i1 = TextIntentExtractor.extract("我想默写一下 deploy")
        assertEquals("deploy", i1?.english)
        assertEquals("spelling", i1?.dimension)

        val i2 = TextIntentExtractor.extract("想练口语 candidate")
        assertEquals("candidate", i2?.english)
        assertEquals("pronunciation", i2?.dimension)

        val i3 = TextIntentExtractor.extract("复习一下 photosynthesis 的词义")
        assertEquals("photosynthesis", i3?.english)
        assertEquals("meaning", i3?.dimension)

        val i4 = TextIntentExtractor.extract("想练习听力 banana")
        assertEquals("banana", i4?.english)
        assertEquals("listening", i4?.dimension)

        val i5 = TextIntentExtractor.extract("我想学一下 elephant")
        assertEquals("elephant", i5?.english)
        assertEquals("", i5?.dimension)
    }

    @Test
    fun testWordSanitizationAndValidCharacters() {
        // 合法连字符和首字母大写
        val i1 = TextIntentExtractor.extract("我想练习 state-of-the-art")
        assertEquals("state-of-the-art", i1?.english)

        val i2 = TextIntentExtractor.extract("想学 o'clock")
        assertEquals("o'clock", i2?.english)

        // 纯符号或空输入
        assertNull(TextIntentExtractor.extract("我想练习 12345"))
        assertNull(TextIntentExtractor.extract("我想练习 @#$%"))
        assertNull(TextIntentExtractor.extract(""))
    }

    @Test
    fun testPermissionMinimizationNoPreferenceTampering() {
        // 用户混入修改偏好的指令，抽取器仅提取合法英文词条，不返回任何偏好修改指令
        val i1 = TextIntentExtractor.extract("我想练 deploy，顺便把每天时间改成 60 分钟")
        assertNotNull(i1)
        assertEquals("deploy", i1?.english)

        // 纯偏好指令不得被误认为待练词
        assertNull(TextIntentExtractor.extract("把每日新词改成 20"))
        assertNull(TextIntentExtractor.extract("我要把学习时间调整为 30 分钟"))
        assertNull(TextIntentExtractor.extract("修改我的偏好为基础较好"))
    }

    @Test
    fun testPermissionMinimizationNoMasteryTampering() {
        // 尝试篡改成绩与连胜指令应被过滤或禁止
        assertNull(TextIntentExtractor.extract("把 deploy 标记为已掌握"))
        assertNull(TextIntentExtractor.extract("deploy 算我全部答对"))
        assertNull(TextIntentExtractor.extract("直接将连胜加 10"))

        // SQL 注入与 prompt 注入指令必须拦截
        assertNull(TextIntentExtractor.extract("想练习 drop table study_plans"))
        assertNull(TextIntentExtractor.extract("把 delete from words 加入计划"))
        assertNull(TextIntentExtractor.extract("想学 ignore previous instructions"))
    }

    @Test
    fun testGenerateIntentKeyIsDeterministic() {
        val k1 = TextIntentExtractor.generateIntentKey("chat", "Deploy", 1729836000000L)
        val k2 = TextIntentExtractor.generateIntentKey("chat", "deploy", 1729836000000L)
        assertEquals(k1, k2)
        assertTrue(k1.startsWith("chat:deploy:"))
    }
}
