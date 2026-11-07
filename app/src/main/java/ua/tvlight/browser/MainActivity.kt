package ua.tvlight.browser

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.WebSettings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.app.AlertDialog
import android.content.Context
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.app.DownloadManager
import android.os.Environment
import android.net.Uri
import android.widget.Toast
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
    private var cursorMode = false
    private var cursorX = 300f
    private var cursorY = 300f

    private val prefs by lazy { getSharedPreferences("browser", Context.MODE_PRIVATE) }
    private val blockedHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adnxs.com", "taboola.com", "outbrain.com", "popads.net",
        "adsrvr.org", "criteo.com", "scorecardresearch.com", "pubmatic.com",
        "rubiconproject.com", "openx.net", "smartadserver.com"
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
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tool(label: String, action: () -> Unit) {
            tools.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        tool("★") { bookmark() }
        tool("Закладки") { showBookmarks() }
        tool("Історія") { showHistory() }
        tool("Меню") { showMenu() }
        tool("Курсор") { cursorMode = !cursorMode; Toast.makeText(this, if (cursorMode) "Курсор: стрілки — рух, OK — натискання" else "Звичайне керування", Toast.LENGTH_LONG).show() }
        root.addView(bar)
        root.addView(tools)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.setSupportMultipleWindows(false)
        web.settings.cacheMode = WebSettings.LOAD_DEFAULT
        web.settings.javaScriptCanOpenWindowsAutomatically = false
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                address.setText(url ?: "")
                if (!url.isNullOrBlank() && url.startsWith("https://")) addHistory(url)
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val host = request?.url?.host?.lowercase(Locale.ROOT) ?: return null
                if (prefs.getBoolean("adblock", true) && blockedHosts.any { host == it || host.endsWith(".$it") }) {
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
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                addContentView(view, ViewGroup.LayoutParams(-1, -1))
            }
            override fun onHideCustomView() { closeFullscreen() }
        }
        web.setDownloadListener { url, userAgent, disposition, mime, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mime)
                request.addRequestHeader("User-Agent", userAgent ?: "")
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, android.webkit.URLUtil.guessFileName(url, disposition, mime))
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                Toast.makeText(this, "Завантаження розпочато", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Не вдалося завантажити файл", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        web.requestFocus()
        setContentView(root)
        navigate(prefs.getString("home", "https://www.google.com") ?: "https://www.google.com")
    }

    private fun entries(key: String): MutableList<String> =
        (prefs.getString(key, "") ?: "").lines().filter { it.isNotBlank() }.toMutableList()

    private fun saveEntries(key: String, values: List<String>) {
        prefs.edit().putString(key, values.take(100).joinToString("\n")).apply()
    }

    private fun addHistory(url: String) {
        val items = entries("history")
        items.remove(url)
        items.add(0, url)
        saveEntries("history", items)
    }

    private fun bookmark() {
        val url = web.url ?: return
        val items = entries("bookmarks")
        if (!items.contains(url)) items.add(0, url)
        saveEntries("bookmarks", items)
        Toast.makeText(this, "Додано в закладки", Toast.LENGTH_SHORT).show()
    }

    private fun showEntries(key: String, title: String) {
        val items = entries(key)
        if (items.isEmpty()) {
            Toast.makeText(this, "Список порожній", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this).setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> navigate(items[which]) }
            .setNegativeButton("Закрити", null).show()
    }

    private fun showBookmarks() = showEntries("bookmarks", "Закладки")
    private fun showHistory() = showEntries("history", "Історія")

    private fun showMenu() {
        val options = arrayOf("AdBlock: увімкнути/вимкнути", "Зробити стартовою", "Очистити кеш", "Очистити історію", "Очистити cookies", "Очистити закладки")
        AlertDialog.Builder(this).setTitle("Налаштування")
            .setItems(options) { _, index ->
                when (index) {
                    0 -> {
                        val next = !prefs.getBoolean("adblock", true)
                        prefs.edit().putBoolean("adblock", next).apply()
                        Toast.makeText(this, if (next) "AdBlock увімкнено" else "AdBlock вимкнено", Toast.LENGTH_SHORT).show()
                        web.reload()
                    }
                    1 -> prefs.edit().putString("home", web.url ?: "https://www.google.com").apply()
                    2 -> web.clearCache(true)
                    3 -> saveEntries("history", emptyList())
                    4 -> CookieManager.getInstance().removeAllCookies(null)
                    5 -> saveEntries("bookmarks", emptyList())
                }
            }.setNegativeButton("Закрити", null).show()
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
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (cursorMode && fullscreen == null && event.action == KeyEvent.ACTION_DOWN) {
            val step = 28f * resources.displayMetrics.density
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> cursorX -= step
                KeyEvent.KEYCODE_DPAD_RIGHT -> cursorX += step
                KeyEvent.KEYCODE_DPAD_UP -> cursorY -= step
                KeyEvent.KEYCODE_DPAD_DOWN -> cursorY += step
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val now = android.os.SystemClock.uptimeMillis()
                    val x = cursorX.coerceIn(0f, web.width.toFloat())
                    val y = cursorY.coerceIn(0f, web.height.toFloat())
                    web.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
                    web.dispatchTouchEvent(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0))
                    return true
                }
                else -> return super.dispatchKeyEvent(event)
            }
            cursorX = cursorX.coerceIn(0f, web.width.toFloat())
            cursorY = cursorY.coerceIn(0f, web.height.toFloat())
            web.invalidate()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        when {
            fullscreen != null -> closeFullscreen()
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        closeFullscreen()
        web.stopLoading()
        web.webChromeClient = null
        web.webViewClient = WebViewClient()
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }
}
