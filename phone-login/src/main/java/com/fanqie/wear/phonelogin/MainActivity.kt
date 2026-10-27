package com.fanqie.wear.phonelogin

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 书阁登录器（手机）。
 *
 * 为什么需要它：番茄的 sessionid 是 HttpOnly，网页 JS 读不到；
 * 而 App 自己的 WebView 能通过 CookieManager 拿到全量 Cookie。
 * 所以这里用 WebView 登录，点一下就把 Cookie 发到同一 WiFi 的手表上。
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var addr: EditText
    private lateinit var status: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("login", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 12)
        }

        status = TextView(this).apply {
            text = "① 在下面登录番茄 ② 点「发送到手表」"
            textSize = 14f
        }
        root.addView(status)

        addr = EditText(this).apply {
            hint = "手表地址，如 192.168.1.12:8765"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("addr", "192.168.1.100:8765"))
            textSize = 14f
        }
        root.addView(addr, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            Button(this).apply {
                text = "打开番茄"
                setOnClickListener {
                    prefs.edit().putString("addr", addr.text.toString().trim()).apply()
                    web.loadUrl("https://fanqienovel.com")
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            Button(this).apply {
                text = "发送到手表"
                setOnClickListener { send() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(row)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        web.loadUrl("https://fanqienovel.com")
    }

    private fun send() {
        val host = addr.text.toString().trim().removePrefix("http://").trimEnd('/')
        if (host.isBlank()) {
            toast("先填手表地址")
            return
        }
        getSharedPreferences("login", MODE_PRIVATE).edit().putString("addr", host).apply()

        val cookie = CookieManager.getInstance().getCookie("https://fanqienovel.com")
            ?: CookieManager.getInstance().getCookie("https://www.fanqienovel.com")
        if (cookie.isNullOrBlank()) {
            status.text = "没拿到 Cookie：先在上面登录，登录成功后再点发送"
            return
        }
        status.text = "正在发送（${cookie.length} 字符）…"

        Thread {
            val result = runCatching {
                val url = URL("http://$host/save")
                val body = "cookie=" + URLEncoder.encode(cookie, "UTF-8")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                conn.inputStream.close()
                code
            }
            runOnUiThread {
                result.onSuccess {
                    status.text = "已发送到手表 ✓"
                    toast("已发送，回手表看看是否登录成功")
                }.onFailure {
                    status.text = "发送失败：${it.message}\n确认手表在「手机扫码登录」页且同一 WiFi"
                    toast("发送失败")
                }
            }
        }.start()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
