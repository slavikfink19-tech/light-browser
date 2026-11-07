package ua.tvlight.browser

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var address: EditText
    private var fullscreen: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var root: LinearLayout

    private val blockedHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adnxs.com", "taboola.com", "outbrain.com", "popads.net"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun navButton(label: String, action: () -> Unit) {
            val button = Button(this).apply { text = label; setOnClickListener { action() } }
            bar.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        navButton("‹") { if (web.canGoBack()) web.goBack() }
        navButton("›") { if (web.canGoForward()) web.goForward() }
        navButton("↻") { web.reload() }
        address = EditText(this).apply {
            setSingleLine(true)
            hint = "Адреса або пошук"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> navigate(text.toString()); true }
        }
        bar.addView(address, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 5f))
        navButton("GO") { navigate(address.text.toString()) }
        root.addView(bar)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.setSupportMultipleWindows(false)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                address.setText(url ?: "")
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val host = request?.url?.host?.lowercase(Locale.ROOT) ?: return null
                if (blockedHosts.any { host == it || host.endsWith(".$it") }) {
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || fullscreen != null) return
                fullscreen = view
                fullscreenCallback = callback
                root.visibility = View.GONE
                addContentView(view, ViewGroup.LayoutParams(-1, -1))
            }
            override fun onHideCustomView() { closeFullscreen() }
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        navigate("https://www.google.com")
    }

    private fun navigate(value: String) {
        val input = value.trim()
        if (input.isEmpty()) return
        val url = when {
            input.startsWith("https://", true) -> input
            input.startsWith("http://", true) -> "https://" + input.substring(7)
            input.contains(" ") || !input.contains(".") ->
                "https://www.google.com/search?q=" + URLEncoder.encode(input, "UTF-8")
            else -> "https://$input"
        }
        web.loadUrl(url)
    }

    private fun closeFullscreen() {
        val view = fullscreen ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        fullscreen = null
        root.visibility = View.VISIBLE
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    override fun onBackPressed() {
        when {
            fullscreen != null -> closeFullscreen()
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
