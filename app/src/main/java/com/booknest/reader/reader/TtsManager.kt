package com.booknest.reader.reader

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** 本地 TTS：不依赖网络，从划词处开始朗读，也是无障碍能力的一部分。 */
class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    private var engine: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    var ready by mutableStateOf(false)
        private set
    var speaking by mutableStateOf(false)
        private set
    private var speed = 1f

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = engine?.setLanguage(Locale.CHINA)
            ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { speaking = true }
                override fun onDone(utteranceId: String?) { speaking = false }
                @Deprecated("legacy callback")
                override fun onError(utteranceId: String?) { speaking = false }
                override fun onError(utteranceId: String?, errorCode: Int) { speaking = false }
            })
        }
    }

    fun speak(text: String, rate: Float = 1f) {
        speed = rate.coerceIn(0.5f, 2f)
        if (!ready || text.isBlank()) return
        stop()
        engine?.setSpeechRate(speed)
        val clean = text.replace(Regex("""\s+"""), " ").trim()
        clean.chunked(280).forEachIndexed { index, chunk ->
            val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "booknest_$index") }
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine?.speak(chunk, mode, params, "booknest_$index")
        }
        speaking = true
    }

    fun stop() {
        engine?.stop()
        speaking = false
    }

    fun shutdown() {
        stop()
        engine?.shutdown()
        engine = null
    }
}

/** 零网络约束下的划词翻译：内置常用词离线词库，未收录时如实告知。 */
private val LOCAL_DICTIONARY = mapOf(
    "book" to "书；书籍", "read" to "阅读；读", "reader" to "读者；阅读器", "chapter" to "章节",
    "story" to "故事", "time" to "时间", "life" to "生活；生命", "world" to "世界",
    "light" to "光；轻的", "night" to "夜晚", "day" to "白天；日子", "word" to "词；字",
    "love" to "爱", "heart" to "心；心脏", "mind" to "心智；头脑", "soul" to "灵魂",
    "home" to "家", "friend" to "朋友", "hope" to "希望", "dream" to "梦；梦想",
    "page" to "页", "paper" to "纸", "write" to "写", "writer" to "作者；作家",
    "silence" to "寂静；沉默", "memory" to "记忆", "future" to "未来", "past" to "过去",
    "journey" to "旅程", "river" to "河流", "sea" to "海", "sky" to "天空",
    "sun" to "太阳", "moon" to "月亮", "star" to "星星", "wind" to "风",
    "rain" to "雨", "snow" to "雪", "spring" to "春天；泉水", "autumn" to "秋天",
    "winter" to "冬天", "summer" to "夏天", "morning" to "早晨", "evening" to "傍晚",
    "beautiful" to "美丽的", "quiet" to "安静的", "gentle" to "温柔的", "brave" to "勇敢的",
    "free" to "自由的；免费的", "local" to "本地的", "private" to "私人的；私密的",
    "knowledge" to "知识", "language" to "语言", "truth" to "真理；真相",
    "the" to "这（定冠词）", "and" to "和；并且", "but" to "但是", "with" to "与；带着",
    "from" to "从；来自", "into" to "进入", "through" to "穿过；通过", "without" to "没有；不带有"
)

fun localTranslate(text: String): String {
    val word = text.trim().trim('.', ',', '!', '?', '"', '\'', ' ').lowercase()
    val meaning = LOCAL_DICTIONARY[word]
    return meaning?.let { "【本地词库】$word  ·  $it" }
        ?: "本地词库暂未收录「${text.take(24)}」。\n零网络设计不含在线翻译，可用「复制」或「朗读」继续阅读。"
}
