package com.example.gooble

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.*
import android.speech.*
import android.view.*
import android.accessibilityservice.AccessibilityService
import androidx.core.app.NotificationCompat
import org.json.JSONArray
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
    private var apiKey = ""

    // Agent state
    private var agentActive = false
    private var agentSteps = mutableListOf<AgentStep>()
    private var currentStep = 0
    private var waitingForTap = false

    data class AgentStep(
        val instruction: String,
        val targetX: Float,
        val targetY: Float,
        val action: String = "tap"
    )

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotif())
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val pt = Point(); wm.defaultDisplay.getSize(pt)
        sw = pt.x; sh = pt.y

        try {
            val id = resources.getIdentifier("gooble_cursor", "drawable", packageName)
            if (id != 0) {
                val raw = BitmapFactory.decodeResource(resources, id)
                if (raw != null) cursorBmp = Bitmap.createScaledBitmap(raw, 56, 56, true)
            }
        } catch (e: Exception) { cursorBmp = null }

        fetchKey()
        setupCursor()
        setupBubble()
        startRoaming()

        handler.postDelayed({ showBubble("👀 tap & hold me to talk", 4000) }, 1200)
    }

    private fun fetchKey() {
        Thread {
            try {
                val resp = URL("https://dialpedia.top/gooble/config.php")
                    .openConnection().apply { connectTimeout=8000; readTimeout=8000 }
                    .getInputStream().bufferedReader().readText()
                apiKey = JSONObject(resp).getString("key")
            } catch (e: Exception) {}
        }.start()
    }

    private fun setupCursor() {
        cursorView = object : View(this) {
            val gP = Paint().apply { isAntiAlias = true }
            val bP = Paint().apply { isAntiAlias = true }
            val ringPaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }

            override fun onDraw(canvas: Canvas) {
                // Glow
                if (glowing && glowR > 0f) {
                    gP.color = glowCol
                    gP.maskFilter = BlurMaskFilter(glowR, BlurMaskFilter.Blur.NORMAL)
                    gP.style = Paint.Style.FILL
                    canvas.drawCircle(28f, 28f, 14f + glowR * 0.3f, gP)
                    gP.maskFilter = null
                }

                // Waiting for tap ring
                if (waitingForTap) {
                    ringPaint.color = Color.argb(200, 255, 220, 50)
                    val ringR = 28f + (glowR * 0.5f)
                    canvas.drawCircle(28f, 28f, ringR, ringPaint)
                }

                // Cursor
                if (cursorBmp != null) {
                    canvas.drawBitmap(cursorBmp!!, 0f, 0f, bP)
                } else {
                    val f = Paint().apply { color=Color.WHITE; style=Paint.Style.FILL; isAntiAlias=true }
                    val s = Paint().apply { color=Color.BLACK; style=Paint.Style.STROKE; strokeWidth=3f; isAntiAlias=true }
                    val p = Path().apply {
                        moveTo(6f,2f); lineTo(6f,46f); lineTo(19f,35f)
                        lineTo(27f,52f); lineTo(33f,49f); lineTo(25f,32f)
                        lineTo(40f,32f); close()
                    }
                    canvas.drawPath(p,f); canvas.drawPath(p,s)
                }

                if (glowing) {
                    if (glowGrow) { glowR+=2.5f; if(glowR>22f) glowGrow=false }
                    else { glowR-=2.5f; if(glowR<4f) glowGrow=true }
                    postInvalidateDelayed(28)
                } else if (glowR>0f) { glowR=0f; postInvalidate() }
            }

            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // If agent waiting for tap — advance to next step
                        if (waitingForTap) {
                            vib(longArrayOf(0, 30))
                            waitingForTap = false
                            handler.postDelayed({ nextAgentStep() }, 500)
                            return true
                        }
                        holdTriggered = false
                        handler.postDelayed({
                            if (!holdTriggered) {
                                holdTriggered = true
                                vib(longArrayOf(0,40,60,40))
                                startListening()
                            }
                        }, 600)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (listening) { vib(longArrayOf(0,30)); sr?.stopListening() }
                        holdTriggered = false
                        return true
                    }
                }
                return true
            }
        }
        wm.addView(cursorView, overlayParams(64,64,true).apply { x=px.toInt(); y=py.toInt() })
    }

    private fun setupBubble() {
        bubbleView = object : View(this) {
            val bg = Paint().apply { color=Color.argb(195,10,10,28); style=Paint.Style.FILL; isAntiAlias=true }
            val sh = Paint().apply { color=Color.argb(20,255,255,255); style=Paint.Style.FILL; isAntiAlias=true }
            val br = Paint().apply { color=Color.argb(90,255,255,255); style=Paint.Style.STROKE; strokeWidth=1.5f; isAntiAlias=true }
            val tp = Paint().apply { color=Color.WHITE; textSize=27f; isAntiAlias=true }
            val sp = Paint().apply { color=Color.argb(180,255,220,50); textSize=22f; isAntiAlias=true }
            val dp = Paint().apply { style=Paint.Style.FILL; isAntiAlias=true }

            override fun onDraw(canvas: Canvas) {
                if (!bVisible) return
                val w=width.toFloat(); val h=height.toFloat()
                val r=RectF(6f,6f,w-6f,h-6f)

                // Agent mode — yellow tinted border
                if (agentActive) br.color = Color.argb(180,255,220,50)
                else br.color = Color.argb(90,255,255,255)

                canvas.drawRoundRect(r,20f,20f,bg)
                canvas.drawRoundRect(RectF(6f,6f,w-6f,h*0.42f),20f,20f,sh)
                canvas.drawRoundRect(r,20f,20f,br)

                if (thinking) {
                    val cx=w/2f; val cy=h/2f
                    val t=(System.currentTimeMillis()%900)/300
                    for (i in 0..2) {
                        dp.color=if(t.toInt()==i) Color.argb(255,120,200,255) else Color.argb(70,180,180,255)
                        canvas.drawCircle(cx-22f+i*22f,cy,7f,dp)
                    }
                    postInvalidateDelayed(80)
                } else {
                    // Step counter for agent mode
                    if (agentActive && agentSteps.isNotEmpty()) {
                        canvas.drawText(
                            "step ${currentStep+1}/${agentSteps.size}",
                            16f, 28f, sp
                        )
                    }
                    var y = if(agentActive && agentSteps.isNotEmpty()) 58f else 40f
                    var line=""
                    for (word in bText.split(" ")) {
                        val test=if(line.isEmpty()) word else "$line $word"
                        if (tp.measureText(test)>w-28f) {
                            canvas.drawText(line,16f,y,tp); line=word; y+=34f
                        } else line=test
                    }
                    if (line.isNotEmpty()) canvas.drawText(line,16f,y,tp)

                    if (waitingForTap) {
                        canvas.drawText("👆 tap me to continue",16f,y+36f,sp)
                    }
                }
            }
        }
        bubbleView.visibility = View.GONE
        wm.addView(bubbleView, overlayParams(380,180,false).apply { x=px.toInt()-8; y=py.toInt()+62 })
    }

    private fun overlayParams(w: Int, h: Int, touchable: Boolean) =
        WindowManager.LayoutParams(
            w, h,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            if (touchable)
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            else
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun vib(pattern: LongArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vibrator.vibrate(VibrationEffect.createWaveform(pattern,-1))
            else @Suppress("DEPRECATION") vibrator.vibrate(pattern,-1)
        } catch(e:Exception){}
    }

    private fun startListening() {
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showBubble("speech not available",3000); return
        }
        listening=true; glowing=true
        glowCol=Color.argb(200,80,160,255)
        handler.post { cursorView.invalidate() }
        showBubble("🎤 listening...",12000)

        sr?.destroy()
        sr = SpeechRecognizer.createSpeechRecognizer(this)
        sr?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {
                glowCol=Color.argb(200,60,220,100)
                handler.post { cursorView.invalidate() }
            }
            override fun onResults(results: Bundle?) {
                listening=false; glowing=false
                val heard=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim() ?: ""
                if (heard.isNotEmpty()) {
                    showBubble("\"$heard\"",1800)
                    handler.postDelayed({ processCommand(heard) },1900)
                } else showBubble("didn't catch that 👀",2500)
            }
            override fun onError(error: Int) {
                listening=false; glowing=false
                showBubble(when(error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "didn't catch that 👀"
                    SpeechRecognizer.ERROR_NETWORK -> "no network 😬"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "need mic permission"
                    else -> "try again 👀"
                },2500)
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
                putExtra(RecognizerIntent.EXTRA_LANGUAGE,"en-US")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,1200L)
            })
        } catch(e:Exception) {
            listening=false; glowing=false
            showBubble("mic error",2500)
        }
    }

    private fun processCommand(cmd: String) {
        val lower = cmd.lowercase()
        when {
            // Stop agent
            lower.contains("stop") || lower.contains("cancel") -> {
                stopAgent()
                showBubble("stopped 👀",2000)
            }
            // Basic nav
            lower.contains("go back") || lower=="back" -> {
                GoobleAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                showBubble("going back 👀",1500)
            }
            lower=="home" || lower.contains("go home") -> {
                GoobleAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                showBubble("going home 👀",1500)
            }
            lower.contains("recent") -> {
                GoobleAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                showBubble("recent apps 👀",1500)
            }
            // Open app then guide
            lower.startsWith("open ") && !lower.contains("and") -> {
                val appName = lower.removePrefix("open ").trim()
                showBubble("opening $appName 👀",2000)
                handler.postDelayed({ launchApp(appName) },500)
            }
            // Read screen
            lower.contains("what") && (lower.contains("screen")||lower.contains("this")) -> {
                val screenText = GoobleAccessibilityService.getScreenText()
                if (screenText.isNotEmpty()) askAI("what's on screen: ${screenText.take(300)} summarize in 25 words")
                else showBubble("enable accessibility first 👀",3000)
            }
            // AGENT MODE — anything that sounds like a task
            lower.contains("help me") || lower.contains("create") ||
            lower.contains("make") || lower.contains("edit") ||
            lower.contains("open") || lower.contains("how do") ||
            lower.contains("show me") || lower.contains("guide") -> {
                startAgentMode(cmd)
            }
            else -> askAI(cmd)
        }
    }

    // ─── AGENT MODE ─────────────────────────────────────────────

    private fun startAgentMode(task: String) {
        if (apiKey.isEmpty()) { fetchKey(); showBubble("connecting...",2000); handler.postDelayed({ startAgentMode(task) },2500); return }
        showBubble("planning... 👀",3000)
        glowing=true; glowCol=Color.argb(200,160,80,255)
        handler.post { cursorView.invalidate() }

        // Get screen context
        val screenText = GoobleAccessibilityService.getScreenText().take(400)

        Thread {
            try {
                val conn=(URL("https://api.groq.com/openai/v1/chat/completions")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod="POST"
                    setRequestProperty("Content-Type","application/json")
                    setRequestProperty("Authorization","Bearer $apiKey")
                    connectTimeout=12000; readTimeout=20000; doOutput=true
                }

                val safe = task.replace("\"","'")
                val screen = screenText.replace("\"","'")

                val systemPrompt = """You are Gooble, an AI cursor agent on Android. 
The user wants you to guide them step by step.
Current screen content: $screen
Screen size: ${sw}x${sh}

Return ONLY a JSON array of steps. Each step:
{"step":1,"instruction":"short what to do","x":0.5,"y":0.5,"action":"tap","appToOpen":""}

Rules:
- x,y are 0.0-1.0 fractions of screen width/height
- action is "tap","swipe","open","type"  
- appToOpen only if opening an app
- instruction max 8 words
- max 8 steps
- Return ONLY the JSON array, nothing else"""

                conn.outputStream.write(JSONObject().apply {
                    put("model","llama-3.1-8b-instant")
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role","system"); put("content",systemPrompt) })
                        put(JSONObject().apply { put("role","user"); put("content","Task: $safe") })
                    })
                    put("temperature",0.3)
                }.toString().toByteArray())

                val rawReply = JSONObject(conn.inputStream.bufferedReader().readText())
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()

                // Parse steps
                val jsonStart = rawReply.indexOf('[')
                val jsonEnd = rawReply.lastIndexOf(']') + 1
                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    val stepsJson = JSONArray(rawReply.substring(jsonStart, jsonEnd))
                    agentSteps.clear()
                    for (i in 0 until stepsJson.length()) {
                        val s = stepsJson.getJSONObject(i)
                        val appToOpen = if (s.has("appToOpen")) s.getString("appToOpen") else ""
                        if (appToOpen.isNotEmpty()) {
                            agentSteps.add(AgentStep(
                                s.getString("instruction"),
                                sw * 0.5f, sh * 0.5f,
                                "open:$appToOpen"
                            ))
                        } else {
                            agentSteps.add(AgentStep(
                                s.getString("instruction"),
                                sw * s.getDouble("x").toFloat(),
                                sh * s.getDouble("y").toFloat(),
                                if (s.has("action")) s.getString("action") else "tap"
                            ))
                        }
                    }
                    currentStep = 0
                    agentActive = true
                    handler.post { executeAgentStep() }
                } else {
                    handler.post { glowing=false; showBubble(rawReply.take(100),5000) }
                }
            } catch(e:Exception) {
                handler.post { glowing=false; thinking=false; showBubble("planning failed, try again 👀",3000) }
            }
        }.start()
    }

    private fun executeAgentStep() {
        if (currentStep >= agentSteps.size) {
            finishAgent()
            return
        }
        val step = agentSteps[currentStep]

        // Handle open app action
        if (step.action.startsWith("open:")) {
            val appName = step.action.removePrefix("open:")
            showBubble(step.instruction, 0)
            launchApp(appName)
            handler.postDelayed({ nextAgentStep() }, 2000)
            return
        }

        // Move cursor to target position
        tx = step.targetX
        ty = step.targetY

        // Wait for cursor to arrive then show instruction
        handler.postDelayed({
            glowing = true
            glowCol = Color.argb(200, 255, 220, 50) // yellow = waiting for tap
            waitingForTap = true
            handler.post { cursorView.invalidate() }
            showBubble(step.instruction, 0)
            vib(longArrayOf(0, 50, 100, 50))
        }, 1200)
    }

    private fun nextAgentStep() {
        currentStep++
        if (currentStep >= agentSteps.size) {
            finishAgent()
        } else {
            executeAgentStep()
        }
    }

    private fun finishAgent() {
        agentActive = false
        agentSteps.clear()
        currentStep = 0
        waitingForTap = false
        glowing = false
        handler.post { cursorView.invalidate() }
        showBubble("all done! 🎉",3000)
        vib(longArrayOf(0,50,100,50,100,50))
    }

    private fun stopAgent() {
        agentActive = false
        agentSteps.clear()
        currentStep = 0
        waitingForTap = false
        glowing = false
        handler.post { cursorView.invalidate() }
    }

    // ─── ROAMING (only when agent not active) ───────────────────

    private fun startRoaming() {
        handler.post(object : Runnable {
            override fun run() {
                if (!listening && !agentActive && !waitingForTap) {
                    val m=100f
                    tx=m+(Math.random()*(sw-m*2)).toFloat()
                    ty=m+(Math.random()*(sh-m*2)).toFloat()
                }
                handler.postDelayed(this,3500L+(Math.random()*4000).toLong())
            }
        })
        handler.post(object : Runnable {
            override fun run() {
                vx+=(tx-px)*0.018f; vy+=(ty-py)*0.018f
                vx*=0.91f; vy*=0.91f
                px+=vx; py+=vy
                px=px.coerceIn(0f,(sw-64).toFloat())
                py=py.coerceIn(0f,(sh-64).toFloat())
                try {
                    val cp=cursorView.layoutParams as WindowManager.LayoutParams
                    cp.x=px.toInt(); cp.y=py.toInt()
                    wm.updateViewLayout(cursorView,cp)
                    val bp=bubbleView.layoutParams as WindowManager.LayoutParams
                    bp.x=(px-8f).toInt().coerceIn(8,sw-388)
                    bp.y=(py+62f).toInt().coerceIn(8,sh-163)
                    wm.updateViewLayout(bubbleView,bp)
                } catch(e:Exception){}
                handler.postDelayed(this,16)
            }
        })
    }

    private fun launchApp(name: String) {
        val pm = packageManager
        val packages = mapOf(
            "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "chrome" to "com.android.chrome",
            "youtube" to "com.google.android.youtube",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "telegram" to "org.telegram.messenger",
            "settings" to "com.android.settings",
            "capcut" to "com.lemon.lvoverseas",
            "alight motion" to "com.motionarray.alightmotion",
            "tiktok" to "com.zhiliaoapp.musically",
            "snapchat" to "com.snapchat.android",
            "facebook" to "com.facebook.katana",
            "spotify" to "com.spotify.music",
            "netflix" to "com.netflix.mediaclient",
            "maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "calendar" to "com.google.android.calendar",
            "camera" to "com.android.camera2",
            "gallery" to "com.sec.android.gallery3d",
            "photos" to "com.google.android.apps.photos",
            "files" to "com.google.android.documentsui",
            "play store" to "com.android.vending",
            "phone" to "com.android.dialer",
            "contacts" to "com.android.contacts",
            "messages" to "com.google.android.apps.messaging",
            "calculator" to "com.android.calculator2",
            "clock" to "com.android.deskclock"
        )
        val pkg = packages.entries.firstOrNull { name.contains(it.key) }?.value
        if (pkg != null) {
            try {
                val launch = pm.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                    showBubble("opened $name ✓",2000)
                } else showBubble("$name not installed",2500)
            } catch(e:Exception) { showBubble("couldn't open $name",2500) }
        } else {
            try {
                val match = pm.getInstalledApplications(0).firstOrNull {
                    pm.getApplicationLabel(it).toString().lowercase().contains(name)
                }
                if (match != null) {
                    val launch = pm.getLaunchIntentForPackage(match.packageName)
                    launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    launch?.let { startActivity(it) }
                    showBubble("opened ${pm.getApplicationLabel(match)} ✓",2000)
                } else showBubble("can't find $name 👀",2500)
            } catch(e:Exception) { showBubble("error opening $name",2500) }
        }
    }

    private fun showBubble(text: String, duration: Long=4000) {
        handler.post {
            bText=text; thinking=false
            bubbleView.visibility=View.VISIBLE
            bVisible=true; bubbleView.invalidate()
        }
        if (duration>0) handler.postDelayed({
            handler.post { bubbleView.visibility=View.GONE; bVisible=false }
        }, duration)
    }

    private fun askAI(prompt: String) {
        if (thinking) return
        if (apiKey.isEmpty()) { fetchKey(); showBubble("connecting...",2000); handler.postDelayed({ askAI(prompt) },2500); return }
        thinking=true; glowing=true
        glowCol=Color.argb(200,160,80,255)
        handler.post {
            bText=""; bubbleView.visibility=View.VISIBLE
            bVisible=true; bubbleView.invalidate(); cursorView.invalidate()
        }
        Thread {
            try {
                val conn=(URL("https://api.groq.com/openai/v1/chat/completions")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod="POST"
                    setRequestProperty("Content-Type","application/json")
                    setRequestProperty("Authorization","Bearer $apiKey")
                    connectTimeout=12000; readTimeout=20000; doOutput=true
                }
                val safe=prompt.replace("\"","'").replace("\n"," ")
                conn.outputStream.write("""{"model":"llama-3.1-8b-instant","messages":[{"role":"system","content":"You are Gooble, a witty AI cursor assistant on Android. Max 25 words. Be sharp and helpful."},{"role":"user","content":"$safe"}]}""".toByteArray())
                val reply=JSONObject(conn.inputStream.bufferedReader().readText())
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
                handler.post { thinking=false; glowing=false; showBubble(reply,7000) }
            } catch(e:Exception) {
                handler.post { thinking=false; glowing=false; showBubble("try again 👀",3000) }
            }
        }.start()
    }

    private fun buildNotif(): Notification {
        val id="gooble_ch"
        if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(id,"Gooble",NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this,id)
            .setContentTitle("Gooble 👀")
            .setContentText("Tap & hold to talk | Tap cursor to confirm steps")
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
