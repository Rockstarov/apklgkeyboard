package com.liquidglass.keyboard

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import java.util.Locale

class LiquidKeyboardService : InputMethodService(), GlassKeyboardView.Listener {

    private var kb: GlassKeyboardView? = null
    private val prefs by lazy { getSharedPreferences("liquid_keyboard", Context.MODE_PRIVATE) }

    override fun onCreateInputView(): View {
        val v = GlassKeyboardView(this)
        v.listener = this
        v.setLanguage(savedLanguage())
        kb = v
        applyGlassWindow()
        return v
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onWindowShown() {
        super.onWindowShown()
        applyGlassWindow()
    }

    /** Прозрачное окно + размытие фона под клавиатурой (Android 12+, если устройство позволяет). */
    private fun applyGlassWindow() {
        val w = window?.window ?: return
        try {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                w.setBackgroundBlurRadius(BLUR_RADIUS_PX)
            }
            @Suppress("DEPRECATION")
            w.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                w.isNavigationBarContrastEnforced = false
            }
        } catch (_: Throwable) {
            // без blur клавиатура остаётся полупрозрачной
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val v = kb ?: return
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION

        v.setLanguage(savedLanguage())
        when (cls) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME -> v.showSymbols()

            InputType.TYPE_CLASS_TEXT -> {
                val latinOnly = variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_URI ||
                    variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                if (latinOnly) v.setLanguage("en")
                v.showLetters()
            }

            else -> v.showLetters()
        }
        if (v.shift != GlassKeyboardView.Shift.CAPS) v.shift = GlassKeyboardView.Shift.OFF
        updateAutoShift(allowOff = true)
    }

    // ---------------------------------------------------------------- GlassKeyboardView.Listener

    override fun onText(text: String) {
        currentInputConnection?.commitText(text, 1)
        updateAutoShift(allowOff = false)
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            val before = ic.getTextBeforeCursor(2, 0)
            if (before.isNullOrEmpty()) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            } else {
                val surrogatePair = before.length == 2 &&
                    Character.isHighSurrogate(before[0]) &&
                    Character.isLowSurrogate(before[1])
                ic.deleteSurroundingText(if (surrogatePair) 2 else 1, 0)
            }
        }
        updateAutoShift(allowOff = true)
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        val opts = currentInputEditorInfo?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        updateAutoShift(allowOff = true)
    }

    override fun onSwitchIme() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }

    override fun onLanguageChanged(lang: String) {
        prefs.edit().putString(PREF_LANG, lang).apply()
    }

    // ---------------------------------------------------------------- helpers

    private fun savedLanguage(): String =
        prefs.getString(PREF_LANG, null)
            ?: if (Locale.getDefault().language == "ru") "ru" else "en"

    /** Заглавная буква в начале предложения. */
    private fun updateAutoShift(allowOff: Boolean) {
        val v = kb ?: return
        if (v.shift == GlassKeyboardView.Shift.CAPS) return
        val info = currentInputEditorInfo ?: return
        if (info.inputType == InputType.TYPE_NULL) return
        val caps = currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0
        if (caps != 0) {
            v.shift = GlassKeyboardView.Shift.ON
        } else if (allowOff) {
            v.shift = GlassKeyboardView.Shift.OFF
        }
    }

    private companion object {
        const val PREF_LANG = "lang"
        const val BLUR_RADIUS_PX = 64
    }
}
