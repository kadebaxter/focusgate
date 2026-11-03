package com.kaden.focusgate

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** Small helpers for building the screens in code with a consistent card style. */
object Ui {
    const val BG = 0xFF111317.toInt()
    const val CARD = 0xFF1D2025.toInt()
    const val FIELD = 0xFF2A2E35.toInt()
    const val TEXT = 0xFFE8EAED.toInt()
    const val MUTED = 0xFF9AA0A6.toInt()
    const val ACCENT = 0xFF8AB4F8.toInt()
    const val GOOD = 0xFF81C995.toInt()
    const val BAD = 0xFFF28B82.toInt()

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusDp: Int, ctx: Context) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(ctx, radiusDp).toFloat()
    }

    /** Replaces the activity's content with a scrolling column and returns the column. */
    fun screen(activity: Activity, title: String, subtitle: String? = null): LinearLayout = screen(activity) { col ->
        col.addView(text(activity, title, 28f, bold = true))
        subtitle?.let { col.addView(text(activity, it, 14f, MUTED).apply { setPadding(0, dp(activity, 4), 0, 0) }) }
    }

    /** Same, with a custom [header] (e.g. the home screen's clock). */
    fun screen(activity: Activity, header: (LinearLayout) -> Unit): LinearLayout {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 24))
            // keeps a search field from grabbing focus (and the keyboard) on open
            isFocusableInTouchMode = true
        }
        header(col)

        val scroll = ScrollView(activity).apply {
            setBackgroundColor(BG)
            addView(col)
            // targetSdk 35+ draws edge-to-edge, so keep content clear of the status/nav bars
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        }
        activity.setContentView(scroll)
        return col
    }

    fun card(parent: LinearLayout, title: String, subtitle: String? = null, body: LinearLayout.() -> Unit) {
        val ctx = parent.context
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 16, ctx)
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 16))
        }
        card.addView(text(ctx, title, 18f, bold = true))
        subtitle?.let { card.addView(text(ctx, it, 13f, MUTED).apply { setPadding(0, dp(ctx, 2), 0, 0) }) }
        card.body()
        parent.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ctx, 16) })
    }

    fun text(ctx: Context, s: String, size: Float = 15f, color: Int = TEXT, bold: Boolean = false) =
        TextView(ctx).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    /** A small label above a control, e.g. "Minutes per day". */
    fun LinearLayout.label(s: String) =
        addView(text(context, s, 13f, MUTED).apply { setPadding(0, dp(context, 14), 0, dp(context, 6)) })

    fun LinearLayout.line(s: String, color: Int = TEXT, size: Float = 15f, bold: Boolean = false) =
        addView(text(context, s, size, color, bold).apply { setPadding(0, dp(context, 6), 0, 0) })

    fun LinearLayout.pill(s: String, color: Int) {
        val ctx = context
        addView(text(ctx, s, 13f, BG, bold = true).apply {
            background = rounded(color, 999, ctx)
            setPadding(dp(ctx, 12), dp(ctx, 4), dp(ctx, 12), dp(ctx, 4))
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(ctx, 10) })
    }

    fun LinearLayout.button(
        s: String,
        primary: Boolean = true,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): Button {
        val ctx = context
        val b = Button(ctx).apply {
            text = s
            isAllCaps = false
            textSize = 15f
            setTextColor(if (primary) BG else ACCENT)
            background = rounded(if (primary) ACCENT else FIELD, 12, ctx)
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.4f
            setOnClickListener { onClick() }
        }
        addView(b, LinearLayout.LayoutParams(MATCH_PARENT, dp(ctx, 48)).apply { topMargin = dp(ctx, 12) })
        return b
    }

    fun LinearLayout.field(hint: String, value: String = "", number: Boolean = false, enabled: Boolean = true): EditText {
        val ctx = context
        val e = EditText(ctx).apply {
            this.hint = hint
            setText(value)
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            background = rounded(FIELD, 12, ctx)
            setPadding(dp(ctx, 14), 0, dp(ctx, 14), 0)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            if (number) inputType = InputType.TYPE_CLASS_NUMBER
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.4f
        }
        addView(e, LinearLayout.LayoutParams(MATCH_PARENT, dp(ctx, 48)))
        return e
    }

    fun LinearLayout.progress(value: Long, max: Long) {
        val ctx = context
        addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            this.max = max.toInt().coerceAtLeast(1)
            progress = value.coerceAtMost(max).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ctx, 8) })
    }

    fun LinearLayout.divider() {
        val ctx = context
        addView(View(ctx).apply { setBackgroundColor(FIELD) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(ctx, 1)).apply { topMargin = dp(ctx, 14) })
    }

    /** A tappable list row inside a card, with an optional second line. Rows get dividers between them. */
    fun LinearLayout.row(
        label: String,
        sub: String? = null,
        color: Int = TEXT,
        onLong: (() -> Unit)? = null,
        onClick: (() -> Unit)? = null,
    ) {
        val ctx = context
        if (childCount > 0 && getChildAt(childCount - 1).tag == "row") divider()
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            tag = "row"
            setPadding(0, dp(ctx, 12), 0, dp(ctx, 12))
            val ripple = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            addView(text(ctx, label, 17f, color))
            sub?.let { addView(text(ctx, it, 13f, MUTED).apply { setPadding(0, dp(ctx, 2), 0, 0) }) }
            onClick?.let { c -> setOnClickListener { c() } }
            onLong?.let { l -> setOnLongClickListener { l(); true } }
        }
        addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /** Pills side by side, e.g. "AT HOME" and "IN: MESSAGE". */
    fun LinearLayout.pills(vararg items: Pair<String, Int>) {
        val ctx = context
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for ((s, color) in items) {
            row.addView(text(ctx, s, 13f, BG, bold = true).apply {
                background = rounded(color, 999, ctx)
                setPadding(dp(ctx, 12), dp(ctx, 4), dp(ctx, 12), dp(ctx, 4))
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(ctx, 8) })
        }
        addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ctx, 12) })
    }

    /** One horizontal bar: label and value on top, a single-hue bar scaled to [fraction] below. */
    fun LinearLayout.bar(label: String, value: String, fraction: Float) {
        val ctx = context
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(text(ctx, label, 15f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(text(ctx, value, 15f, MUTED))
        }
        val f = fraction.coerceIn(0.02f, 1f)   // keep tiny values visible
        val track = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(View(ctx).apply { background = rounded(ACCENT, 4, ctx) }, LinearLayout.LayoutParams(0, dp(ctx, 8), f))
            if (f < 1f) addView(View(ctx), LinearLayout.LayoutParams(0, dp(ctx, 8), 1f - f))
        }
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 12), 0, 0)
            addView(top)
            addView(track, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ctx, 6) })
        })
    }

    /** Headline numbers side by side: (value, label). */
    fun LinearLayout.tiles(vararg items: Pair<String, String>) {
        val ctx = context
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for ((value, label) in items) {
            row.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(ctx, value, 24f, bold = true))
                addView(text(ctx, label, 13f, MUTED))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ctx, 12) })
    }

    /** A row of equal-width pill buttons; the [selected] one is highlighted. */
    fun LinearLayout.chips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        val ctx = context
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEachIndexed { i, s ->
            val on = i == selected
            row.addView(compactButton(ctx, s, if (on) BG else TEXT, if (on) ACCENT else FIELD, 999) { onSelect(i) },
                LinearLayout.LayoutParams(0, dp(ctx, 40), 1f).apply { if (i > 0) marginStart = dp(ctx, 6) })
        }
        addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ctx, 8) })
    }

    /** Side-by-side secondary buttons, e.g. Apps / Rename / Delete. */
    fun LinearLayout.buttonRow(vararg actions: Pair<String, () -> Unit>) {
        val ctx = context
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        actions.forEachIndexed { i, (s, onClick) ->
            row.addView(compactButton(ctx, s, ACCENT, FIELD, 12, onClick),
                LinearLayout.LayoutParams(0, dp(ctx, 40), 1f).apply { if (i > 0) marginStart = dp(ctx, 6) })
        }
        addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ctx, 10) })
    }

    private fun compactButton(ctx: Context, s: String, fg: Int, bg: Int, radiusDp: Int, onClick: () -> Unit) =
        Button(ctx).apply {
            text = s
            isAllCaps = false
            textSize = 13f
            setTextColor(fg)
            background = rounded(bg, radiusDp, ctx)
            // the default Button minimums are too wide to fit five across on a phone
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
        }
}
