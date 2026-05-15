package com.example.gooble

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.*
import android.speech.*
import android.view.*
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GoobleService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var cursorView: View
    private lateinit var bubbleView: View
    private lateinit var vibrator: Vibrator
    private var sr: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var sw = 0; private var sh = 0
    private var px = 200f; private var py = 400f
    private var tx = 200f; private var ty = 400f
    private var vx = 0f; private var vy = 0f

    private var bText = ""; private var bVisible = false
    private var thinking = false; private var listening = false
    private var glowing = false; private var glowR = 0f
    private var glowGrow = true
    private var glowCol = Color.argb(200, 80, 160, 255)
    private var cursorBmp: Bitmap? = null
    private var holdTriggered = false

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotif())
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val pt = Point(); wm.defaultDisplay.getSize(pt)
        sw = pt.x; sh = pt.y

        // Load cursor from drawable
        try {
            val id = resources.getIdentifier("gooble_cursor", "drawable", packageName)
            if (id != 0) {
                val raw = BitmapFactory.decodeResource(resources, id)
                if (raw != null) cursorBmp = Bitmap.createScaledBitmap(raw, 56, 56, true)
            }
        } catch (e: Exception) { cursorBmp = null }

        setupCursor()
        setupBubble()
        startRoaming()

        handler.postDelayed({ showBubble("👀 tap & hold me to talk", 4000) }, 1200)
    }

    private fun setupCursor() {
        cursorView = object : View(this) {
            val gPaint = Paint().apply { isAntiAlias = true }
            val bPaint = Paint().apply { isAntiAlias = true }

            override fun onDraw(canvas: Canvas) {
                // Glow
                if (glowing && glowR > 0f) {
                    gPaint.color = glowCol
                    gPaint.maskFilter = BlurMaskFilter(glowR, BlurMaskFilter.Blur.NORMAL)
                    gPaint.style = Paint.Style.FILL
                    canvas.drawCircle(28f, 28f, 14f + glowR * 0.3f, gPaint)
                    gPaint.maskFilter = null
                }
                // Cursor
                if (cursorBmp != null) {
                    canvas.drawBitmap(cursorBmp!!, 0f, 0f, bPaint)
                } else {
                    val f = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
                    val s = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }
                    val p = Path().apply {
                        moveTo(6f,2f); lineTo(6f,46f); lineTo(19f,35f)
                        lineTo(27f,52f); lineTo(33f,49f); lineTo(25f,32f)
                        lineTo(40f,32f); close()
                    }
                    canvas.drawPath(p, f); canvas.drawPath(p, s)
                }
                // Pulse
                if (glowing) {
                    if (glowGrow) { glowR += 2.5f; if (glowR > 22f) glowGrow = false }
                    else { glowR -= 2.5f; if (glowR < 4f) glowGrow = true }
                    postInvalidateDelayed(28)
                } else if (glowR > 0f) { glowR = 0f; postInvalidate() }
            }

            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        holdTriggered = false
                        handler.postDelayed({
                            if (!holdTriggered) {
                                holdTriggered = true
                                vib(longArrayOf(0, 40, 60, 40))
                                startListening()
                            }
                        }, 600)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (listening) {
                            vib(longArrayOf(0, 30))
                            sr?.stopListening()
                        }
                        holdTriggered = false
                        return true
                    }
                }
                return true
            }
        }

        wm.addView(cursorView, overlayParams(64, 64).apply {
            x = px.toInt(); y = py.toInt()
        })
    }

    private fun setupBubble() {
        bubbleView = object : View(this) {
            val bg = Paint().apply { color = Color.argb(195,10,10,28); style=Paint.Style.FILL; isAntiAlias=true }
            val sh = Paint().apply { color = Color.argb(20,255,255,255); style=Paint.Style.FILL; isAntiAlias=true }
            val br = Paint().apply { color = Color.argb(90,255,255,255); style=Paint.Style.STROKE; strokeWidth=1.5f; isAntiAlias=true }
            val tp = Paint().apply { color = Color.WHITE; textSize=27f; isAntiAlias=true }
            val dp = Paint().apply { style=Paint.Style.FILL; isAntiAlias=true }

            override fun onDraw(canvas: Canvas) {
                if (!bVisible) return
                val w=width.toFloat(); val h=height.toFloat()
                val r = RectF(6f,6f,w-6f,h-6f)
                canvas.drawRoundRect(r,20f,20f,bg)
                canvas.drawRoundRect(RectF(6f,6f,w-6f,h*0.42f),20f,20f,sh)
                canvas.drawRoundRect(r,20f,20f,br)

                if (thinking) {
                    val cx=w/2f; val cy=h/2f
                    val t=(System.currentTimeMillis()%900)/300
                    for (i in 0..2) {
                        dp.color = if(t.toInt()==i) Color.argb(255,120,200,255) else Color.argb(70,180,180,255)
                        canvas.drawCircle(cx-22f+i*22f,cy,7f,dp)
                    }
                    postInvalidateDelayed(80)
                } else {
                    var y=40f; var line=""
                    for (word in bText.split(" ")) {
                        val test = if(line.isEmpty()) word else "$line $word"
                        if (tp.measureText(test) > w-28f) {
                            canvas.drawText(line,16f,y,tp); line=word; y+=34f
                        } else line=test
                    }
                    if (line.isNotEmpty()) canvas.drawText(line,16f,y,tp)
                }
            }
        }

        bubbleView.visibility = View.GONE
        wm.addView(bubbleView, overlayParams(380, 155).apply {
            x = px.toInt() - 8; y = py.toInt() + 62
        })
    }

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun vib(pattern: LongArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            else @Suppress("DEPRECATION") vibrator.vibrate(pattern, -1)
        } catch (e: Exception) {}
    }

    private fun startListening() {
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showBubble("speech not available", 3000); return
        }
        listening = true
        glowing = true
        glowCol = Color.argb(200, 80, 160, 255)
        handler.post { cursorView.invalidate() }
        showBubble("🎤 listening...", 12000)

        sr?.destroy()
        sr = SpeechRecognizer.createSpeechRecognizer(this)
        sr?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {
                glowCol = Color.argb(200, 60, 220, 100)
                handler.post { cursorView.invalidate() }
            }
            override fun onResults(results: Bundle?) {
                listening = false; glowing = false
                val heard = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim() ?: ""
                if (heard.isNotEmpty()) {
                    showBubble("\"$heard\"", 1800)
                    handler.postDelayed({ askHermes(heard) }, 1900)
                } else showBubble("didn't catch that 👀", 2500)
            }
            override fun onError(error: Int) {
                listening = false; glowing = false
                showBubble(when(error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "didn't catch that 👀"
                    SpeechRecognizer.ERROR_NETWORK -> "no network 😬"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "need mic permission"
                    else -> "try again 👀"
                }, 2500)
            }
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })

        try {
            sr?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            })
        } catch (e: Exception) {
            listening = false; glowing = false
            showBubble("mic error", 2500)
        }
    }

    private fun startRoaming() {
        handler.post(object : Runnable {
            override fun run() {
                if (!listening) {
                    val m = 100f
                    tx = m + (Math.random()*(sw-m*2)).toFloat()
                    ty = m + (Math.random()*(sh-m*2)).toFloat()
                }
                handler.postDelayed(this, 3000L+(Math.random()*4000).toLong())
            }
        })
        handler.post(object : Runnable {
            override fun run() {
                vx += (tx-px)*0.018f; vy += (ty-py)*0.018f
                vx *= 0.91f; vy *= 0.91f
                px += vx; py += vy
                px = px.coerceIn(0f,(sw-64).toFloat())
                py = py.coerceIn(0f,(sh-64).toFloat())
                try {
                    val cp = cursorView.layoutParams as WindowManager.LayoutParams
                    cp.x=px.toInt(); cp.y=py.toInt()
                    wm.updateViewLayout(cursorView, cp)
                    val bp = bubbleView.layoutParams as WindowManager.LayoutParams
                    bp.x=(px-8f).toInt().coerceIn(8,sw-388)
                    bp.y=(py+62f).toInt().coerceIn(8,sh-163)
                    wm.updateViewLayout(bubbleView, bp)
                } catch(e:Exception){}
                handler.postDelayed(this, 16)
            }
        })
    }

    private fun showBubble(text: String, duration: Long = 4000) {
        handler.post {
            bText = text; thinking = false
            bubbleView.visibility = View.VISIBLE
            bVisible = true; bubbleView.invalidate()
        }
        if (duration > 0) handler.postDelayed({
            handler.post { bubbleView.visibility=View.GONE; bVisible=false }
        }, duration)
    }

    private fun askHermes(prompt: String) {
        if (thinking) return
        thinking = true; glowing = true
        glowCol = Color.argb(200, 160, 80, 255)
        handler.post {
            bText=""; bubbleView.visibility=View.VISIBLE
            bVisible=true; bubbleView.invalidate(); cursorView.invalidate()
        }
        Thread {
            try {
                val conn = (URL("https://openrouter.ai/api/v1/chat/completions")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type","application/json")
                    setRequestProperty("Authorization","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")
                    setRequestProperty("HTTP-Referer","https://gooble.app")
                    connectTimeout=12000; readTimeout=20000; doOutput=true
                }
                val safe = prompt.replace("\"","'").replace("\n"," ")
                conn.outputStream.write("""{"model":"nousresearch/hermes-3-llama-3.1-405b:free","messages":[{"role":"system","content":"You are Gooble, a witty AI cursor assistant on the user phone screen. Max 25 words. Be sharp and helpful."},{"role":"user","content":"$safe"}]}""".toByteArray())
                val reply = JSONObject(conn.inputStream.bufferedReader().readText())
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
                handler.post { thinking=false; glowing=false; showBubble(reply,7000) }
            } catch(e:Exception) {
                handler.post { thinking=false; glowing=false; showBubble("network issue 👀",3000) }
            }
        }.start()
    }

    private fun buildNotif(): Notification {
        val id = "gooble_ch"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(id,"Gooble",NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this,id)
            .setContentTitle("Gooble 👀")
            .setContentText("Tap & hold Gooble to talk")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        sr?.destroy()
        try { wm.removeView(cursorView) } catch(e:Exception){}
        try { wm.removeView(bubbleView) } catch(e:Exception){}
    }
}
