package ua.tvlight.browser

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.Paint
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.HorizontalScrollView
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebSettings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceError
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import android.text.InputType
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.app.DownloadManager
import android.os.Environment
import android.net.Uri
import android.widget.Toast
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.GridLayout
import android.content.res.Configuration
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var adBlockEngine: AdBlockEngine
    private lateinit var address: EditText
    private var fullscreen: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenContainer: FrameLayout? = null
    private var fullscreenPointer: View? = null
    private lateinit var root: LinearLayout
    private lateinit var cursorOverlay: View
    private lateinit var menuCursorOverlay: View
    private lateinit var page: FrameLayout
    private lateinit var homePanel: LinearLayout
    private lateinit var toolbar: LinearLayout
    private val tabUrls = mutableListOf<String>()
    private var activeTab = 0
    private var immersive = false
    private val homeTab = "tvlight://home"
    private var pendingBackup: ByteArray? = null
    private var pendingImportPassword: CharArray? = null
    private val defaultCategories = listOf("Онлайн-кінотеатри", "Аніме", "Новини", "Соцмережі", "Різне")
    private fun categoryName(i: Int) = prefs.getString("category_name_$i", defaultCategories[i]) ?: defaultCategories[i]
    private fun categoryKey(i: Int) = "category_urls_$i"
    @Volatile private var currentSiteHost: String? = null
    private var cursorMode = false
    private val isTelevision by lazy { (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION }
    private var cursorX = 300f
    private var cursorY = 300f
    private fun updateEdgeScroll() {
        // Scrolling only follows actual remote-control directional key presses.
    }

    private val prefs by lazy { getSharedPreferences("browser", Context.MODE_PRIVATE) }
    private val blockedHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adnxs.com", "taboola.com", "outbrain.com", "popads.net",
        "adsrvr.org", "criteo.com", "scorecardresearch.com", "pubmatic.com",
        "rubiconproject.com", "openx.net", "smartadserver.com",
        "beton.ua", "beton.com.ua", "exoclick.com", "exosrv.com", "trafficjunky.net",
        "propellerads.com", "onclickads.net", "adsterra.com", "hilltopads.net",
        "bidgear.com", "mgid.com", "revcontent.com", "popcash.net",
        "juicyads.com", "clickadu.com", "ad-maven.com", "adcash.com"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adBlockEngine = AdBlockEngine(applicationContext)
        cursorMode = isTelevision
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(3), dp(4), dp(3))
            setBackgroundColor(Color.rgb(8, 22, 45))
        }
        fun navButton(label: String, action: () -> Unit) {
            bar.addView(styledButton(label, Color.rgb(15, 44, 79), Color.rgb(39, 111, 169), action),
                LinearLayout.LayoutParams(dp(35), dp(42)).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        navButton("⌂") { showHome() }
        navButton("‹") { if (web.canGoBack()) web.goBack() else showHome() }
        navButton("›") { if (web.canGoForward()) web.goForward() }
        
        address = EditText(this).apply {
            setSingleLine(true)
            hint = "Пошук або адреса сайту"
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(142, 175, 208))
            setPadding(dp(13), 0, dp(8), 0)
            background = rounded(Color.rgb(14, 38, 70), Color.rgb(71, 109, 148))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> navigate(text.toString()); true }
        }
        bar.addView(address, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            setMargins(dp(5), 0, dp(5), 0)
        })
        navButton("⋮") { showBrowserMenu() }
        toolbar = bar
        root.addView(bar)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.textZoom = prefs.getInt("zoom", 100)
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.setSupportMultipleWindows(false)
        web.settings.cacheMode = WebSettings.LOAD_DEFAULT
        web.settings.javaScriptCanOpenWindowsAutomatically = false
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                currentSiteHost = Uri.parse(url ?: "").host?.lowercase(Locale.ROOT)
                if (isAdBlockActive()) view?.postDelayed({ if (isAdBlockActive()) applyCosmeticFilters() }, 400)
                if (isAdBlockActive()) view?.postDelayed({ if (isAdBlockActive()) applyCosmeticFilters() }, 1800)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                address.setText(url ?: "")
                if (!url.isNullOrBlank() && tabUrls.isNotEmpty() && activeTab in tabUrls.indices) tabUrls[activeTab] = url
                if (isAdBlockActive()) applyCosmeticFilters()
                if (!url.isNullOrBlank() && url.startsWith("https://")) addHistory(url)
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (isAdBlockActive() && adBlockEngine.shouldBlock(
                        request, currentSiteHost, true)) {
                    return adBlockEngine.emptyResponse()
                }
                return null
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || fullscreen != null) return
                fullscreen = view
                updateScreenAwake()
                fullscreenCallback = callback
                val container = FrameLayout(this@MainActivity)
                container.setBackgroundColor(Color.BLACK)
                container.addView(view, FrameLayout.LayoutParams(-1, -1))
                val pointerView = object : View(this@MainActivity) {
                    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        style = Paint.Style.FILL
                    }
                    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        style = Paint.Style.STROKE
                        strokeWidth = dp(2).toFloat()
                    }
                    override fun onDraw(canvas: Canvas) {
                        if (!cursorMode) return
                        val x = cursorX.coerceIn(0f, width.toFloat())
                        val y = cursorY.coerceIn(0f, height.toFloat())
                        val arrow = android.graphics.Path().apply {
                            moveTo(x, y)
                            lineTo(x, y + dp(14))
                            lineTo(x + dp(4), y + dp(11))
                            lineTo(x + dp(6), y + dp(16))
                            lineTo(x + dp(9), y + dp(15))
                            lineTo(x + dp(7), y + dp(9))
                            lineTo(x + dp(12), y + dp(9))
                            close()
                        }
                        canvas.drawPath(arrow, fillPaint)
                        canvas.drawPath(arrow, borderPaint)
                    }
                }
                pointerView.isClickable = false
                pointerView.isFocusable = false
                container.addView(pointerView, FrameLayout.LayoutParams(-1, -1))
                fullscreenContainer = container
                fullscreenPointer = pointerView
                root.visibility = View.GONE
                menuCursorOverlay.visibility = View.GONE
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                addContentView(container, ViewGroup.LayoutParams(-1, -1))
                container.post {
                    cursorX = container.width / 2f
                    cursorY = container.height / 2f
                    pointerView.invalidate()
                }
            }
            override fun onHideCustomView() { closeFullscreen() }
        }
        web.setDownloadListener { url, userAgent, disposition, mime, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mime)
                request.addRequestHeader("User-Agent", userAgent ?: "")
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, android.webkit.URLUtil.guessFileName(url, disposition, mime))
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                Toast.makeText(this, "Завантаження розпочато", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Не вдалося завантажити файл", Toast.LENGTH_SHORT).show()
            }
        }
        page = FrameLayout(this)
        page.addView(web, FrameLayout.LayoutParams(-1, -1))
        cursorOverlay = object : View(this) {
            private val pointer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
                setShadowLayer(dp(3).toFloat(), dp(1).toFloat(), dp(2).toFloat(), Color.BLACK)
            }
            private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(20, 35, 52)
                style = Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
                strokeJoin = Paint.Join.ROUND
            }
            override fun onDraw(canvas: Canvas) {
                if (!cursorMode || page.visibility != View.VISIBLE) return
                val x = cursorX.coerceIn(0f, width.toFloat())
                val y = cursorY.coerceIn(0f, height.toFloat())
                val arrow = android.graphics.Path().apply {
                    moveTo(x, y)
                    lineTo(x, y + dp(14))
                    lineTo(x + dp(4), y + dp(11))
                    lineTo(x + dp(6), y + dp(16))
                    lineTo(x + dp(9), y + dp(15))
                    lineTo(x + dp(7), y + dp(9))
                    lineTo(x + dp(12), y + dp(9))
                    close()
                }
                canvas.drawPath(arrow, pointer)
                canvas.drawPath(arrow, outline)
            }
        }
        page.addView(cursorOverlay, FrameLayout.LayoutParams(-1, -1))
        cursorOverlay.isClickable = false
        cursorOverlay.isFocusable = false
        root.addView(page, LinearLayout.LayoutParams(-1, 0, 1f))
        homePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            setBackgroundColor(Color.rgb(13, 29, 49))
        }
        root.addView(homePanel, LinearLayout.LayoutParams(-1, 0, 1f))
        web.requestFocus()
        setContentView(root)
        menuCursorOverlay = object : View(this) {
            private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
            private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat()
            }
            override fun onDraw(canvas: Canvas) {
                if (!cursorMode || homePanel.visibility != View.VISIBLE || fullscreen != null) return
                val x = cursorX.coerceIn(0f, width.toFloat())
                val y = cursorY.coerceIn(0f, height.toFloat())
                val arrow = android.graphics.Path().apply {
                    moveTo(x, y); lineTo(x, y + dp(14)); lineTo(x + dp(4), y + dp(11))
                    lineTo(x + dp(6), y + dp(16)); lineTo(x + dp(9), y + dp(15))
                    lineTo(x + dp(7), y + dp(9)); lineTo(x + dp(12), y + dp(9)); close()
                }
                canvas.drawPath(arrow, fill); canvas.drawPath(arrow, border)
            }
        }
        menuCursorOverlay.isClickable = false
        menuCursorOverlay.isFocusable = false
        (findViewById<ViewGroup>(android.R.id.content)).addView(
            menuCursorOverlay, ViewGroup.LayoutParams(-1, -1))
        showHome()
    }

    private fun updateScreenAwake() {
        val watchingOrBrowsing = page.visibility == View.VISIBLE || fullscreen != null
        if (watchingOrBrowsing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun focusFirstMenuControl() {
        if (!isTelevision) return
        homePanel.post {
            fun find(view: View): View? {
                if (view.visibility != View.VISIBLE) return null
                if (view.isFocusable && view.isClickable && view.isEnabled) return view
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) {
                        val found = find(view.getChildAt(i))
                        if (found != null) return found
                    }
                }
                return null
            }
            find(homePanel)?.requestFocus()
        }
    }

    private fun styledButton(label: String, fill: Int, border: Int, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            background = rounded(fill, border)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            setOnFocusChangeListener { _, focused ->
                background = rounded(if (focused) Color.rgb(48, 99, 144) else fill, if (focused) Color.WHITE else border)
                alpha = if (focused) 1f else 0.94f
                scaleX = if (focused) 1.035f else 1f
                scaleY = if (focused) 1.035f else 1f
                elevation = if (focused) dp(8).toFloat() else dp(2).toFloat()
            }
        }


    private fun panel(title: String, render: (LinearLayout) -> Unit) {
        toolbar.visibility = View.GONE
        page.visibility = View.GONE
        updateScreenAwake()
        homePanel.visibility = View.VISIBLE
        menuCursorOverlay.post {
            cursorX = menuCursorOverlay.width / 2f
            cursorY = menuCursorOverlay.height / 2f
            menuCursorOverlay.invalidate()
        }
        homePanel.removeAllViews()
        homePanel.setPadding(dp(14), dp(12), dp(14), dp(12))
        homePanel.setBackgroundColor(Color.rgb(13, 29, 49))
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(styledButton("‹", Color.rgb(31, 55, 78), Color.rgb(55, 82, 108)) {
            showHome()
        }, LinearLayout.LayoutParams(dp(48), dp(46)))
        heading.addView(TextView(this).apply {
            text = title
            textSize = 21f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(237, 244, 250))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(54), 1f))
        body.addView(heading)
        render(body)
        scroll.addView(body)
        homePanel.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        focusFirstMenuControl()
    }

    private fun panelAction(body: LinearLayout, title: String, subtitle: String = "", action: () -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(17), dp(10), dp(12), dp(10))
            background = rounded(Color.rgb(34, 57, 82), Color.rgb(57, 81, 107))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            setOnFocusChangeListener { _, focused ->
                background = rounded(
                    if (focused) Color.rgb(48, 99, 144) else Color.rgb(34, 57, 82),
                    if (focused) Color.WHITE else Color.rgb(57, 81, 107)
                )
                scaleX = if (focused) 1.015f else 1f
                scaleY = if (focused) 1.015f else 1f
            }
        }
        container.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.rgb(242, 247, 253))
            setTypeface(null, Typeface.BOLD)
        })
        if (subtitle.isNotEmpty()) container.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(Color.rgb(180, 200, 218))
            maxLines = 2
        })
        body.addView(container, LinearLayout.LayoutParams(-1, if (subtitle.isEmpty()) dp(57) else dp(72)).apply {
            setMargins(dp(2), dp(4), dp(2), dp(4))
        })
    }

    private fun showHome() {
        toolbar.visibility = View.GONE
        page.visibility = View.GONE
        updateScreenAwake()
        homePanel.visibility = View.VISIBLE
        if (::menuCursorOverlay.isInitialized) {
            menuCursorOverlay.visibility = View.VISIBLE
            menuCursorOverlay.invalidate()
        }
        homePanel.removeAllViews()
        homePanel.setPadding(dp(16), dp(8), dp(16), dp(12))
        val wide = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            resources.configuration.smallestScreenWidthDp >= 600
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        homePanel.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        focusFirstMenuControl()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "◈  TV LIGHT BROWSER"
            textSize = if (wide) 24f else 19f
            setTextColor(Color.rgb(236, 247, 255))
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(styledButton("⚙", Color.rgb(17, 46, 83), Color.rgb(46, 127, 191)) { showMenu() },
            LinearLayout.LayoutParams(dp(46), dp(42)))
        content.addView(header)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Головна", "Вкладки", "Закладки", "Історія").forEachIndexed { index, name ->
            val action: () -> Unit = when (index) {
                0 -> { { showHome() } }
                1 -> { { showTabs() } }
                2 -> { { showBookmarks() } }
                else -> { { showHistory() } }
            }
            tabs.addView(styledButton(name, if (index == 0) Color.rgb(15, 79, 132) else Color.rgb(9, 29, 56),
                if (index == 0) Color.rgb(82, 140, 188) else Color.rgb(49, 75, 101), action),
                LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        content.addView(tabs)
        val search = EditText(this).apply {
            hint = "⌕  Пошук або введіть адресу сайту..."
            setSingleLine(true)
            textSize = if (wide) 17f else 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(146, 181, 218))
            setPadding(dp(18), 0, dp(18), 0)
            background = rounded(Color.rgb(14, 41, 76), Color.rgb(92, 146, 186))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> navigate(text.toString()); true }
        }
        content.addView(search, LinearLayout.LayoutParams(-1, dp(54)).apply {
            setMargins(dp(3), dp(15), dp(3), dp(9))
        })
        fun heading(text: String) {
            content.addView(TextView(this).apply {
                this.text = text
                textSize = if (wide) 19f else 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.rgb(229, 244, 255))
                setPadding(dp(5), dp(12), 0, dp(10))
            })
        }
        content.addView(styledButton("◉  Мій профіль", Color.rgb(15, 47, 83), Color.rgb(43, 144, 210)) {
            showProfile()
        }.apply { textSize = 16f }, LinearLayout.LayoutParams(-1, dp(52)).apply {
            setMargins(dp(3), dp(7), dp(3), dp(4))
        })
        content.addView(styledButton("✈  Telegram Web", Color.rgb(14, 64, 103), Color.rgb(41, 163, 230)) {
            navigate("https://web.telegram.org/")
        }.apply { textSize = 16f }, LinearLayout.LayoutParams(-1, dp(52)).apply {
            setMargins(dp(3), dp(7), dp(3), dp(7))
        })
        heading("Швидкий доступ")
        val tiles = defaultCategories.indices.map { i ->
            Triple(categoryName(i), listOf(Color.rgb(78,28,137),Color.rgb(8,77,142),Color.rgb(6,100,79),Color.rgb(130,88,15),Color.rgb(26,65,125))[i],
                { showCategory(i) })
        }
        val columns = if (wide) 5 else 2
        val grid = GridLayout(this).apply { columnCount = columns }
        val gap = dp(6)
        val available = resources.displayMetrics.widthPixels - dp(32) - dp(12)
        val tileWidth = (available / columns) - gap * 2
        tiles.forEach { (label, fill, action) ->
            val tile = styledButton(label.replace("\\n", "\n"), fill, Color.rgb(88, 121, 151), action).apply {
                textSize = if (wide) 17f else 15f
                elevation = dp(4).toFloat()
                setShadowLayer(dp(3).toFloat(), 0f, 0f, Color.rgb(30, 130, 220))
            }
            tile.setOnLongClickListener { renameCategory(tiles.indexOfFirst { it.first == label }); true }
            grid.addView(tile, GridLayout.LayoutParams().apply {
                width = tileWidth.coerceAtLeast(dp(110))
                height = if (wide) dp(122) else dp(112)
                setMargins(gap, gap, gap, gap)
            })
        }
        content.addView(grid)
        heading("★  Швидкі закладки")
        val shortcuts = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        entries("bookmarks").take(12).forEach { url ->
            val host = Uri.parse(url).host?.removePrefix("www.") ?: url
            row.addView(styledButton(host, Color.rgb(17, 43, 78), Color.rgb(40, 100, 155)) { navigate(url) },
                LinearLayout.LayoutParams(dp(160), dp(49)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        row.addView(styledButton("+ Додати", Color.rgb(18, 52, 91), Color.rgb(35, 155, 231)) { promptBookmark() },
            LinearLayout.LayoutParams(dp(145), dp(49)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        shortcuts.addView(row)
        content.addView(shortcuts)
    }

    private fun rounded(fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = 16f * resources.displayMetrics.density
            setStroke((2 * resources.displayMetrics.density).toInt(), stroke)
        }

    private fun promptBookmark() {
        val input = EditText(this).apply { hint = "https://example.com" }
        AlertDialog.Builder(this).setTitle("Додати закладку")
            .setView(input)
            .setPositiveButton("Додати") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) {
                    val url = if (value.startsWith("https://")) value else "https://$value"
                    val items = entries("bookmarks")
                    if (!items.contains(url)) items.add(0, url)
                    saveEntries("bookmarks", items)
                    chooseCategory(url) { showHome() }
                }
            }.setNegativeButton("Скасувати", null).show()
    }

    private fun openDownloads() {
        try {
            startActivity(android.content.Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (_: Exception) {
            Toast.makeText(this, "Менеджер завантажень недоступний", Toast.LENGTH_SHORT).show()
        }
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
        chooseCategory(url) {}
    }

    private fun showEntries(key: String, title: String) {
        panel(title) { body ->
            val items = entries(key)
            if (items.isEmpty()) panelAction(body, "Список порожній", "Додайте сайт, щоб він з’явився тут") {}
            items.forEach { url ->
                panelAction(body, Uri.parse(url).host ?: url, url) { navigate(url) }
            }
            if (key == "history") panelAction(body, "Очистити історію") {
                confirmClear("history", "історію")
            }
        }
    }

    private fun showBookmarks() = showEntries("bookmarks", "Закладки")
    private fun showHistory() = showEntries("history", "Історія перегляду")

    private fun themedMenu(title: String, items: List<Pair<String, () -> Unit>>) {
        panel(title) { body ->
            items.forEach { (label, action) -> panelAction(body, label, action = action) }
        }
    }

    private fun notifyUser(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun passwordPrompt(title: String, action: (CharArray) -> Unit) {
        val input = EditText(this).apply {
            hint = "Пароль (мінімум 8 символів)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("Продовжити") { _, _ ->
                val pass = input.text.toString().toCharArray()
                if (pass.size < 8) { pass.fill(0.toChar()); notifyUser("Пароль закороткий") }
                else action(pass)
            }.setNegativeButton("Скасувати", null).show()
    }

    private fun exportBackup() {
        passwordPrompt("Створити зашифровану копію") { pass ->
            try {
                val data = JSONObject()
                val keys = listOf("bookmarks", "history", "home", "search_engine", "zoom", "adblock_exceptions", "profile_name", "profile_avatar") +
                    defaultCategories.indices.flatMap { listOf(categoryKey(it), "category_name_$it") }
                keys.forEach { key ->
                    when (val value = prefs.all[key]) {
                        is String -> data.put(key, value)
                        is Int -> data.put(key, value)
                    }
                }
                pendingBackup = BackupCrypto.encrypt(
                    JSONObject().put("format", "tvlight-data-v1").put("preferences", data).toString(), pass)
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE, "TVLight-backup.tvlb")
                }, 9101)
            } catch (_: Exception) { pendingBackup = null; notifyUser("Помилка експорту") }
            finally { pass.fill(0.toChar()) }
        }
    }

    private fun importBackup() {
        passwordPrompt("Відновити зашифровану копію") { pass ->
            pendingImportPassword?.fill(0.toChar())
            pendingImportPassword = pass
            try {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, 9102)
            } catch (_: Exception) {
                pendingImportPassword?.fill(0.toChar())
                pendingImportPassword = null
                notifyUser("Помилка вибору файлу")
            }
        }
    }

    @Deprecated("Legacy Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 9101) {
            val bytes = pendingBackup
            pendingBackup = null
            if (resultCode == Activity.RESULT_OK && data?.data != null && bytes != null) {
                try {
                    contentResolver.openOutputStream(data.data!!)?.use { it.write(bytes) }
                        ?: throw IllegalStateException("No output stream")
                    notifyUser("Копію збережено")
                } catch (_: Exception) { notifyUser("Помилка запису") }
            }
        }
        if (requestCode == 9102) {
            val pass = pendingImportPassword
            pendingImportPassword = null
            try {
                if (resultCode == Activity.RESULT_OK && data?.data != null && pass != null) {
                    val bytes = contentResolver.openInputStream(data.data!!)?.use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            if (out.size() + n > 5_000_000) throw IllegalArgumentException("Large file")
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray()
                    } ?: throw IllegalStateException("No input stream")
                    val json = JSONObject(BackupCrypto.decrypt(bytes, pass))
                    require(json.getString("format") == "tvlight-data-v1")
                    val values = json.getJSONObject("preferences")
                    val allowed = (listOf("bookmarks", "history", "home", "search_engine", "zoom", "adblock_exceptions") +
                        defaultCategories.indices.flatMap { listOf(categoryKey(it), "category_name_$it") }).toSet()
                    val editor = prefs.edit()
                    values.keys().forEach { key ->
                        if (key in allowed) {
                            val value = values.get(key)
                            if (value is String && value.length <= 50000) editor.putString(key, value)
                            else if (value is Int && key in setOf("search_engine", "zoom")) editor.putInt(key, value)
                        }
                    }
                    editor.apply()
                    web.settings.textZoom = prefs.getInt("zoom", 100)
                    showHome()
                    notifyUser("Копію відновлено")
                }
            } catch (_: Exception) { notifyUser("Невірний пароль або пошкоджений файл") }
            finally { pass?.fill(0.toChar()) }
        }
    }



    private fun showProfile() {
        val name = prefs.getString("profile_name", "Користувач") ?: "Користувач"
        val avatar = prefs.getString("profile_avatar", "◉") ?: "◉"
        themedMenu("$avatar  Мій профіль — $name", listOf(
            "✎ Змінити ім’я" to { editProfileName() },
            "◈ Обрати аватар" to { chooseProfileAvatar() },
            "✈ Відкрити Telegram Web" to { navigate("https://web.telegram.org/") },
            "⇧ Експорт зашифрованої копії" to { exportBackup() },
            "⇩ Імпорт зашифрованої копії" to { importBackup() }
        ))
    }

    private fun editProfileName() {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(prefs.getString("profile_name", "Користувач"))
            selectAll()
        }
        AlertDialog.Builder(this).setTitle("Ім’я профілю").setView(input)
            .setPositiveButton("Зберегти") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && name.length <= 50) {
                    prefs.edit().putString("profile_name", name).apply()
                    showHome()
                } else notifyUser("Введіть ім’я до 50 символів")
            }.setNegativeButton("Скасувати", null).show()
    }

    private fun chooseProfileAvatar() {
        val avatars = arrayOf("◉", "◈", "★", "◆", "●", "♟")
        AlertDialog.Builder(this).setTitle("Аватар профілю")
            .setItems(avatars) { _, index ->
                prefs.edit().putString("profile_avatar", avatars[index]).apply()
                showHome()
            }.setNegativeButton("Скасувати", null).show()
    }

    private fun showMenu() {
        themedMenu("⚙ Налаштування TV Light Browser", listOf(
            "⌂ Стартова сторінка" to { setHomePage() },
            "⌕ Пошукова система" to { chooseSearchEngine() },
            "▣ Масштаб сторінок" to { chooseZoom() },
            "🛡 AdBlock — максимальний" to { themedMenu("AdBlock", listOf(
                "Виняток для поточного сайту" to { toggleSiteException() }
            )) },
            "⇧ Експорт зашифрованої копії" to { exportBackup() },
            "⇩ Імпорт зашифрованої копії" to { importBackup() },
            "◷ Очистити історію" to { confirmClear("history", "історію") },
            "★ Очистити закладки" to { confirmClear("bookmarks", "закладки") },
            "◉ Очистити cookies" to { AlertDialog.Builder(this)
                .setTitle("Очистити cookies?").setPositiveButton("Очистити") { _, _ ->
                    CookieManager.getInstance().removeAllCookies(null); notifyUser("Cookies очищено")
                }.setNegativeButton("Скасувати", null).show() },
            "▤ Очистити кеш" to { web.clearCache(true); notifyUser("Кеш очищено") }
        ))
    }

    private fun showBrowserMenu() {
        themedMenu("◈ TV LIGHT BROWSER", listOf(
            "★ Зберегти сайт" to { bookmark() },
            "↻ Оновити" to { web.reload() },
            "▣ Вкладки" to { showTabs() },
            "+ Нова вкладка" to { newTab() },
            "★ Закладки" to { showBookmarks() },
            "◷ Історія" to { showHistory() },
            "⇩ Завантаження" to { openDownloads() },
            "✈ Telegram Web" to { navigate("https://web.telegram.org/") },
            (if (immersive) "□ Вийти з повного екрана" else "▣ Повний екран") to {
                immersive = !immersive
                toolbar.visibility = if (immersive) View.GONE else View.VISIBLE
                if (immersive) window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            },
            "⊕ Курсор Android TV" to {
                cursorMode = !cursorMode; cursorOverlay.invalidate(); updateEdgeScroll()
                notifyUser(if (cursorMode) "Курсор увімкнено" else "Курсор вимкнено")
            },
            "⚙ Налаштування" to { showMenu() }
        ))
    }

    private fun setHomePage() {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(prefs.getString("home", "https://www.google.com"))
        }
        AlertDialog.Builder(this).setTitle("Стартова сторінка").setView(input)
            .setPositiveButton("Зберегти") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("https://")) prefs.edit().putString("home", url).apply()
                else notifyUser("Введіть HTTPS-адресу")
            }.setNegativeButton("Скасувати", null).show()
    }

    private fun chooseSearchEngine() {
        val names = arrayOf("Google", "DuckDuckGo", "Bing")
        AlertDialog.Builder(this).setTitle("Пошукова система")
            .setSingleChoiceItems(names, prefs.getInt("search_engine", 0)) { dialog, index ->
                prefs.edit().putInt("search_engine", index).apply(); dialog.dismiss()
            }.setNegativeButton("Скасувати", null).show()
    }

    private fun chooseZoom() {
        val values = intArrayOf(75, 90, 100, 110, 125, 150)
        AlertDialog.Builder(this).setTitle("Масштаб сторінок")
            .setSingleChoiceItems(values.map { "$it%" }.toTypedArray(),
                values.indexOf(prefs.getInt("zoom", 100))) { dialog, index ->
                prefs.edit().putInt("zoom", values[index]).apply()
                web.settings.textZoom = values[index]; dialog.dismiss()
            }.setNegativeButton("Скасувати", null).show()
    }

    private fun confirmClear(key: String, label: String) {
        AlertDialog.Builder(this).setTitle("Очистити $label?")
            .setMessage("Цю дію не можна скасувати.")
            .setPositiveButton("Очистити") { _, _ -> saveEntries(key, emptyList()); notifyUser("Очищено") }
            .setNegativeButton("Скасувати", null).show()
    }

    private fun newTab() {
        if (tabUrls.isEmpty()) tabUrls.add(homeTab)
        else if (activeTab in tabUrls.indices) if (page.visibility == View.VISIBLE) tabUrls[activeTab] = web.url ?: tabUrls[activeTab]
        tabUrls.add(homeTab)
        activeTab = tabUrls.lastIndex
        showHome()
    }

    private fun showTabs() {
        if (tabUrls.isEmpty()) tabUrls.add(homeTab)
        panel("Вкладки") { body ->
            tabUrls.toList().forEachIndexed { i, url ->
                val title = if (url == homeTab) "Головна сторінка" else Uri.parse(url).host ?: url
                panelAction(body, "${i + 1}. $title", if (url == homeTab) "TV Light Browser" else url) {
                    activeTab = i
                    if (url == homeTab) showHome() else navigate(url)
                }
                panelAction(body, "× Закрити вкладку ${i + 1}") {
                    if (tabUrls.size > 1) {
                        tabUrls.removeAt(i)
                        activeTab = (i - 1).coerceAtLeast(0)
                        showTabs()
                    } else {
                        tabUrls[0] = homeTab
                        activeTab = 0
                        showHome()
                    }
                }
            }
            panelAction(body, "+ Нова вкладка") { newTab() }
        }
    }

    private fun chooseCategory(url: String, done: () -> Unit) {
        AlertDialog.Builder(this).setTitle("Додати до швидкого доступу")
            .setItems(defaultCategories.indices.map { categoryName(it) }.toTypedArray()) { _, i ->
                val items = entries(categoryKey(i))
                if (!items.contains(url)) items.add(0,url)
                saveEntries(categoryKey(i),items)
                Toast.makeText(this, "Збережено: ${categoryName(i)}", Toast.LENGTH_SHORT).show()
                done()
            }.setNegativeButton("Лише закладки") { _, _ -> done() }.show()
    }

    private fun renameCategory(i: Int) {
        if (i !in defaultCategories.indices) return
        val input = EditText(this).apply { setText(categoryName(i)); selectAll() }
        AlertDialog.Builder(this).setTitle("Перейменувати категорію").setView(input)
            .setPositiveButton("Зберегти") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) { prefs.edit().putString("category_name_$i", name).apply(); showHome() }
            }.setNegativeButton("Скасувати", null).show()
    }

    private fun showCategory(i: Int) {
        panel("Категорія: ${categoryName(i)}") { body ->
            panelAction(body, "+ Додати сайт") {
                val input = EditText(this).apply { hint = "https://example.com" }
                AlertDialog.Builder(this).setTitle("Додати сайт").setView(input)
                    .setPositiveButton("Додати") { _, _ ->
                        val value = input.text.toString().trim()
                        if (value.isNotEmpty()) {
                            val url = if (value.startsWith("https://")) value else "https://$value"
                            val saved = entries(categoryKey(i))
                            if (!saved.contains(url)) saved.add(url)
                            saveEntries(categoryKey(i), saved)
                            showCategory(i)
                        }
                    }.setNegativeButton("Скасувати", null).show()
            }
            panelAction(body, "✎ Перейменувати категорію") { renameCategory(i) }
            entries(categoryKey(i)).forEach { url ->
                panelAction(body, Uri.parse(url).host ?: url, url) { navigate(url) }
                panelAction(body, "⋮ Керувати сайтом") {
                    themedMenu("Керування сайтом", listOf(
                        "Відкрити" to { navigate(url) },
                        "Видалити з категорії" to {
                            val items = entries(categoryKey(i))
                            items.remove(url)
                            saveEntries(categoryKey(i), items)
                            showCategory(i)
                        }
                    ))
                }
            }
        }
    }

    private fun isAdBlockActive(): Boolean {
        val host = currentSiteHost ?: return true
        return host !in entries("adblock_exceptions")
    }

    private fun toggleSiteException() {
        val host = Uri.parse(web.url ?: "").host?.lowercase(Locale.ROOT)
        if (host.isNullOrBlank()) {
            Toast.makeText(this, "Спочатку відкрийте сайт", Toast.LENGTH_SHORT).show()
            return
        }
        val exceptions = entries("adblock_exceptions")
        val added = if (exceptions.contains(host)) { exceptions.remove(host); false }
                    else { exceptions.add(host); true }
        saveEntries("adblock_exceptions", exceptions)
        Toast.makeText(this, if (added) "AdBlock вимкнено для $host" else "AdBlock увімкнено для $host", Toast.LENGTH_LONG).show()
        web.reload()
    }

    private fun applyCosmeticFilters() {
        val selectors = mutableListOf(
            "iframe[src*='doubleclick']", "iframe[src*='adservice']",
            "[id^='google_ads']", ".adsbygoogle", "[data-ad-slot]",
            "[class*='ad-banner']", "[class*='banner-ad']",
            "[class*='popup-ad']", "[id*='popup-ad']",
            "a[href*='beton.ua']", "a[href*='beton.com.ua']",
            "a[href*='cosmolot']", "a[href*='cosmo-lot']"
        )
        if (true) {
            selectors.addAll(listOf(
                "[class*='advertisement']", "[id*='advertisement']",
                "[class*='ad_banner']", "[class*='banner_ad']",
                "iframe[src*='ads']", "[class*='popunder']",
                "[id*='popunder']", "[class*='ad-overlay']"
            ))
        }
        val host = Uri.parse(web.url ?: "").host?.lowercase(Locale.ROOT) ?: ""
        val siteRules = if (host == "uakino.best" || host.endsWith(".uakino.best")) listOf(
            "a[href*='cosmolot']", "a[href*='cosmo-lot']",
            "a[href*='beton']", "a[href*='casino']",
            "[class*='banner']", "[id*='banner']",
            "[class*='advert']", "[id*='advert']",
            "[class*='popunder']", "[id*='popunder']",
            "iframe[src*='adserver']"
        ) else emptyList()
        val css = (selectors + siteRules).distinct().joinToString(",") +
            "{display:none!important;visibility:hidden!important;max-height:0!important;}"
        val script = """
            (function(){
              try {
                var id='tvlight-adblock-style';
                var el=document.getElementById(id);
                if(!el){el=document.createElement('style');el.id=id;(document.head||document.documentElement).appendChild(el);}
                el.textContent=${org.json.JSONObject.quote(css)};
                if(window.__tvlightAdObserver)return;
                var selectors=${org.json.JSONArray((selectors + siteRules).distinct()).toString()};
                var removeAds=function(){
                  try {
                    selectors.forEach(function(sel){
                      document.querySelectorAll(sel).forEach(function(node){
                        if(node.tagName==='VIDEO'||node.tagName==='SOURCE')return;
                        node.style.setProperty('display','none','important');
                      });
                    });
                  }catch(e){}
                };
                removeAds();
                var observer=new MutationObserver(function(){removeAds();});
                observer.observe(document.documentElement,{childList:true,subtree:true});
                window.__tvlightAdObserver=observer;
              }catch(e){}
            })();
        """.trimIndent()
        web.evaluateJavascript(script, null)
    }

    private fun navigate(value: String) {
        val input = value.trim()
        if (input.isEmpty()) return
        val url = when {
            input.startsWith("https://", true) -> input
            input.startsWith("http://", true) -> "https://" + input.substring(7)
            input.contains(" ") || !input.contains(".") ->
                (when (prefs.getInt("search_engine", 0)) {
                    1 -> "https://duckduckgo.com/?q="
                    2 -> "https://www.bing.com/search?q="
                    else -> "https://www.google.com/search?q="
                }) + URLEncoder.encode(input, "UTF-8")
            else -> "https://$input"
        }
        // Dismiss the search keyboard before switching from the home screen.
        val focusedInput = currentFocus
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow((focusedInput ?: homePanel).windowToken, 0)
        focusedInput?.clearFocus()
        homePanel.visibility = View.GONE
        menuCursorOverlay.visibility = View.GONE
        menuCursorOverlay.invalidate()
        toolbar.visibility = if (immersive) View.GONE else View.VISIBLE
        page.visibility = View.VISIBLE
        updateScreenAwake()
        cursorX = (web.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) / 2f
        cursorY = (web.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) / 2f
        cursorOverlay.invalidate()
        updateEdgeScroll()
        if (tabUrls.isEmpty()) { tabUrls.add(url); activeTab = 0 }
        else if (activeTab in tabUrls.indices) tabUrls[activeTab] = url
        web.loadUrl(url)
    }

    private fun closeFullscreen() {
        val view = fullscreen ?: return
        val container = fullscreenContainer
        (container?.parent as? ViewGroup)?.removeView(container)
        (view.parent as? ViewGroup)?.removeView(view)
        fullscreen = null
        updateScreenAwake()
        fullscreenContainer = null
        fullscreenPointer = null
        root.visibility = View.VISIBLE
        menuCursorOverlay.visibility = if (homePanel.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        web.requestFocus()
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val player = fullscreen
        val inMenu = player == null && homePanel.visibility == View.VISIBLE
        val activePointer = if (player != null) fullscreenPointer else if (inMenu) menuCursorOverlay else cursorOverlay
        val target = player ?: if (inMenu) homePanel else web
        val active = player != null || page.visibility == View.VISIBLE || inMenu
        if (cursorMode && active && (player != null || inMenu || !address.hasFocus()) &&
            event.action == KeyEvent.ACTION_DOWN) {
            val step = dp(if (event.repeatCount > 8) 58 else 30).toFloat()
            val edge = dp(20).toFloat()
            val width = if (inMenu) menuCursorOverlay.width else (if (player != null) fullscreenContainer?.width else web.width) ?: web.width
            val height = if (inMenu) menuCursorOverlay.height else (if (player != null) fullscreenContainer?.height else web.height) ?: web.height
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    cursorX = (cursorX - step).coerceAtLeast(0f)
                    if (!inMenu && player == null && cursorX <= edge) web.scrollBy(-dp(90), 0)
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    cursorX = (cursorX + step).coerceAtMost(width.toFloat())
                    if (!inMenu && player == null && cursorX >= width - edge) web.scrollBy(dp(90), 0)
                }
                KeyEvent.KEYCODE_DPAD_UP ->
                    { cursorY = (cursorY - step).coerceAtLeast(0f)
                      if (inMenu && cursorY <= edge) (homePanel.getChildAt(0) as? android.widget.ScrollView)?.scrollBy(0, -dp(110))
                      else if (player == null && cursorY <= edge) web.scrollBy(0, -dp(110)) }
                KeyEvent.KEYCODE_DPAD_DOWN ->
                    { cursorY = (cursorY + step).coerceAtMost(height.toFloat())
                      if (inMenu && cursorY >= height - edge) (homePanel.getChildAt(0) as? android.widget.ScrollView)?.scrollBy(0, dp(110))
                      else if (player == null && cursorY >= height - edge) web.scrollBy(0, dp(110)) }
                KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (player == null) web.scrollBy(0, (web.height * 0.8f).toInt())
                }
                KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (player == null) web.scrollBy(0, -(web.height * 0.8f).toInt())
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.repeatCount > 0) return true
                    val now = android.os.SystemClock.uptimeMillis()
                    val x = cursorX.coerceIn(0f, width.toFloat())
                    val y = cursorY.coerceIn(0f, height.toFloat())
                    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                    val up = MotionEvent.obtain(now, now + 65, MotionEvent.ACTION_UP, x, y, 0)
                    if (inMenu) {
                        val overlayLocation = IntArray(2)
                        val targetLocation = IntArray(2)
                        menuCursorOverlay.getLocationOnScreen(overlayLocation)
                        target.getLocationOnScreen(targetLocation)
                        down.offsetLocation((overlayLocation[0] - targetLocation[0]).toFloat(),
                            (overlayLocation[1] - targetLocation[1]).toFloat())
                        up.offsetLocation((overlayLocation[0] - targetLocation[0]).toFloat(),
                            (overlayLocation[1] - targetLocation[1]).toFloat())
                    }
                    target.dispatchTouchEvent(down)
                    target.dispatchTouchEvent(up)
                    down.recycle()
                    up.recycle()
                    return true
                }
                else -> return super.dispatchKeyEvent(event)
            }
            activePointer?.invalidate()
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
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        closeFullscreen()
        web.stopLoading()
        web.webChromeClient = null
        web.webViewClient = WebViewClient()
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }
}
