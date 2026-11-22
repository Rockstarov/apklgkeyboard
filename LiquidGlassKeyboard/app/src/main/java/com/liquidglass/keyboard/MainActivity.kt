package com.liquidglass.keyboard

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max

/** Экран настройки: включить клавиатуру, выбрать её и попробовать ввод. */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF6D4AFF.toInt(), 0xFF2E7BFF.toInt(), 0xFF14C8B8.toInt())
            )
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        content.addView(TextView(this).apply {
            setText(R.string.title)
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        content.addView(TextView(this).apply {
            setText(R.string.subtitle)
            setTextColor(Color.argb(210, 255, 255, 255))
            textSize = 16f
            setPadding(0, dp(4), 0, dp(18))
        })

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = glass(dp(18).toFloat(), 40, 110)
        }
        content.addView(status, params(bottom = 14))

        content.addView(
            card(R.string.step1_title).also {
                it.addView(glassButton(R.string.step1_btn) {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                })
            }
        )

        content.addView(
            card(R.string.step2_title).also {
                it.addView(glassButton(R.string.step2_btn) {
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showInputMethodPicker()
                })
            }
        )

        content.addView(
            card(R.string.step3_title).also {
                it.addView(EditText(this).apply {
                    setHint(R.string.test_hint)
                    setHintTextColor(Color.argb(150, 255, 255, 255))
                    setTextColor(Color.WHITE)
                    textSize = 17f
                    gravity = Gravity.TOP or Gravity.START
                    minLines = 3
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = glass(dp(16).toFloat(), 30, 110)
                }, params(top = 12))
            }
        )

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
            addView(content)
        }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val i = insetsOf(insets)
            v.setPadding(i[0], i[1], i[2], i[3])
            insets
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure
            .getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        status.setText(
            when {
                current -> R.string.status_active
                enabled -> R.string.status_enabled
                else -> R.string.status_off
            }
        )
    }

    // ------------------------------------------------------------------ UI helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun glass(radius: Float, fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(Color.argb(fill, 255, 255, 255))
        setStroke(dp(1), Color.argb(stroke, 255, 255, 255))
    }

    private fun params(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(top), 0, dp(bottom)) }

    private fun card(titleRes: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(18))
        background = glass(dp(24).toFloat(), 46, 130)
        layoutParams = params(bottom = 14)
        addView(TextView(context).apply {
            setText(titleRes)
            setTextColor(Color.WHITE)
            textSize = 18f
        })
    }

    private fun glassButton(textRes: Int, onClick: () -> Unit) = Button(this).apply {
        setText(textRes)
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 15f
        stateListAnimator = null
        background = glass(dp(16).toFloat(), 70, 170)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48)
        ).apply { topMargin = dp(12) }
        setOnClickListener { onClick() }
    }

    @Suppress("DEPRECATION")
    private fun insetsOf(ins: WindowInsets): IntArray =
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = ins.getInsets(WindowInsets.Type.ime())
            intArrayOf(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
        } else {
            intArrayOf(
                ins.systemWindowInsetLeft,
                ins.systemWindowInsetTop,
                ins.systemWindowInsetRight,
                ins.systemWindowInsetBottom
            )
        }
}
