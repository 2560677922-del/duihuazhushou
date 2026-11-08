package com.dbpg.duihuazhushou.chat.render

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import com.dbpg.duihuazhushou.Insets
import com.dbpg.duihuazhushou.R
import com.dbpg.duihuazhushou.appearance.Appearance
import com.dbpg.duihuazhushou.appearance.ThemePalette

/**
 * 卡片查看器：把一条美化 HTML 用**真 WebView**打开。
 *
 * 为什么需要它：列表里为了滚动性能把卡片画成了位图，代价是文字不能选中、
 * CSS 动画不动。用户要看细节/复制文字时，进这里 —— 全屏、单实例、可选中。
 */
class CardViewerActivity : Activity() {

    companion object {
        const val EXTRA_HTML = "html"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TITLE = "title"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_card_viewer)
        Insets.applyAll(this)

        val html = intent.getStringExtra(EXTRA_HTML).orEmpty()
        val plain = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val cfg = Appearance.load(this)

        findViewById<View>(R.id.card_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.card_title).text =
            intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "卡片"

        findViewById<TextView>(R.id.card_copy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("消息", plain))
            Toast.makeText(this, "已复制原文", Toast.LENGTH_SHORT).show()
        }

        val web = findViewById<WebView>(R.id.card_web)
        if (html.isBlank()) {
            finish()
            return
        }

        val s = web.settings
        s.javaScriptEnabled = cfg.allowScript
        s.domStorageEnabled = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.loadsImagesAutomatically = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        web.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest,
            ): Boolean = true
        }

        val color = String.format(
            "#%06X",
            0xFFFFFF and ThemePalette.fontColorOn(cfg.fontColor, cfg.bgBaseColor),
        )
        web.loadDataWithBaseURL(
            null,
            """
            <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              html,body{margin:0;padding:12px;background:transparent;}
              body{color:$color;font-family:${ThemePalette.fontCssFamily(cfg.fontFamily)};
                   font-size:${cfg.textSizeSp}px;line-height:${cfg.lineSpacing};
                   word-break:break-word;overflow-wrap:anywhere;}
              img,video,canvas{max-width:100%;height:auto;}
              table{max-width:100%;border-collapse:collapse;}
              pre{white-space:pre-wrap;word-break:break-word;overflow-x:auto;}
            </style></head><body>$html</body></html>
            """.trimIndent(),
            "text/html",
            "utf-8",
            null,
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        // WebView 属于本 Activity，必须销毁，否则整个 Activity 泄漏
        runCatching { (findViewById<WebView>(R.id.card_web))?.destroy() }
    }
}
