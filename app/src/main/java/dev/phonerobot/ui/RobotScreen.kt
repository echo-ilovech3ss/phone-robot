package dev.phonerobot.ui

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.camera.view.PreviewView

class RobotScreen(private val context: Context) {
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(28))
        setBackgroundColor(Color.rgb(9, 19, 26))
    }
    val root = ScrollView(context).apply { isFillViewport = true; addView(content) }
    val title = label("PHONE ROBOT", 26f)
    val mode = label("Starting", 16f)
    val face = RobotFaceView(context)
    val preview = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FIT_CENTER
        contentDescription = "Front camera preview, processed only on this device"
    }
    val vision = label("Camera paused", 14f)
    val speech = label("Preparing offline voice", 14f)
    val privacy = label("Local camera + voice · AI off · No servos connected", 13f)
    val reply = label("Tap Listen or type a command. Say help for offline commands.", 18f)
    val transcript = label("", 14f)
    val input = EditText(context).apply {
        hint = "Type a command or question"
        contentDescription = "Command or question"
        setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        filters = arrayOf(android.text.InputFilter.LengthFilter(1000))
        minHeight = dp(48)
    }
    val send = button("Send")
    val listen = button("Listen")
    val stop = button("Stop")
    val track = button("Track me")
    val sleep = button("Sleep / Wake")
    val people = button("People")
    val settings = button("AI settings")
    val camera = button("Camera on / off")
    val voiceSetup = button("Install offline voice")
    init {
        content.addView(title); content.addView(privacy); content.addView(mode)
        content.addView(face, LinearLayout.LayoutParams(-1, dp(210)))
        content.addView(reply); content.addView(transcript); content.addView(input)
        row(listen, send, stop); row(track, sleep); row(people, settings)
        content.addView(speech); content.addView(voiceSetup)
        content.addView(preview, LinearLayout.LayoutParams(-1, dp(150)))
        content.addView(vision); content.addView(camera)
        mode.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        reply.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float) = TextView(content.context).apply {
        this.text = text; textSize = size; setTextColor(Color.rgb(232, 243, 245)); setPadding(0, 8, 0, 8)
    }
    private fun button(text: String) = Button(content.context).apply { this.text = text; isAllCaps = false; minHeight = dp(48) }
    private fun row(vararg views: View) {
        val row = LinearLayout(content.context)
        views.forEach { row.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        content.addView(row)
    }
}
