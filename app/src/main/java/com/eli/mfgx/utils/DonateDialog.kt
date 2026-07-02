package com.eli.mfgx.utils

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import androidx.core.graphics.toColorInt
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.eli.mfgx.R

object DonateDialog {

    private const val KOFI_URL = "https://ko-fi.com/eli_lei"
    private const val EDGEX_KOFI_URL = "https://ko-fi.com/fantasy1999"

    fun show(context: Context) {
        val dp = { dp: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics).toInt() }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(20f), dp(24f), dp(8f))
        }

        // Title
        TextView(context).apply {
            text = context.getString(R.string.donate_title)
            textSize = 18f
            setTextColor(Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
            root.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8f) })
        }

        // Subtitle
        TextView(context).apply {
            text = context.getString(R.string.donate_subtitle)
            textSize = 13f
            setTextColor("#666666".toColorInt())
            root.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(20f) })
        }

        // Buttons row 1 (Alipay + WeChat)
        val buttonRow1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val buttonParams = LinearLayout.LayoutParams(0, dp(44f), 1f).also {
            it.marginEnd = dp(8f)
        }

        // Alipay button
        val alipayBtn = makeButton(context, context.getString(R.string.donate_alipay), "#1677FF".toColorInt())
        buttonRow1.addView(alipayBtn, buttonParams)

        // WeChat button
        val wechatParams = LinearLayout.LayoutParams(0, dp(44f), 1f).also {
            it.marginStart = dp(8f)
        }
        val wechatBtn = makeButton(context, context.getString(R.string.donate_wechat), "#07C160".toColorInt())
        buttonRow1.addView(wechatBtn, wechatParams)

        root.addView(buttonRow1, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(8f) })

        // Buttons row 2 (Ko-fi only)
        val buttonRow2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val kofiBtn = makeButton(context, context.getString(R.string.donate_kofi), "#13C3FF".toColorInt())
        buttonRow2.addView(kofiBtn, LinearLayout.LayoutParams(0, dp(44f), 1f))

        root.addView(buttonRow2, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(4f) })

        // EdgeX author link
        TextView(context).apply {
            text = context.getString(R.string.donate_edgex_author)
            textSize = 12f
            setTextColor("#1677FF".toColorInt())
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setTypeface(null, android.graphics.Typeface.BOLD)
            root.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(12f) })
            setOnClickListener {
                dialog.dismiss()
                showEdgeXAuthorDialog(context)
            }
        }

        val shape = GradientDrawable().apply {
            setColor("#F5F5F5".toColorInt())
            cornerRadius = dp(12f).toFloat()
        }
        val bg = InsetDrawable(shape, dp(24f))

        val dialog = AlertDialog.Builder(context)
            .setView(root)
            .create()

        dialog.window?.setBackgroundDrawable(bg)

        alipayBtn.setOnClickListener {
            dialog.dismiss()
            showAlipayQr(context)
        }
        wechatBtn.setOnClickListener {
            dialog.dismiss()
            showWechatQr(context)
        }
        kofiBtn.setOnClickListener {
            dialog.dismiss()
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KOFI_URL)))
        }

        dialog.show()
    }

    private fun makeButton(context: Context, label: String, color: Int): TextView {
        val dp = { dp: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics).toInt() }
        return TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(8f).toFloat()
            }
        }
    }

    fun openKofi(context: Context) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KOFI_URL)))
    }

    fun showAlipayQr(context: Context) {
        val dp = { dp: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics).toInt() }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24f), dp(20f), dp(24f), dp(20f))
        }

        TextView(context).apply {
            text = context.getString(R.string.donate_alipay_scan)
            textSize = 15f
            setTextColor(Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            container.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(16f) })
        }

        val qrImageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val resId = context.resources.getIdentifier("ic_alipay_qr", "drawable", context.packageName)
            if (resId != 0) setImageResource(resId) else visibility = View.GONE
        }
        container.addView(qrImageView, LinearLayout.LayoutParams(dp(200f), dp(200f)))

        val shape = GradientDrawable().apply {
            setColor("#F5F5F5".toColorInt())
            cornerRadius = dp(12f).toFloat()
        }

        AlertDialog.Builder(context)
            .setView(container)
            .create()
            .also {
                it.window?.setBackgroundDrawable(InsetDrawable(shape, dp(24f)))
                it.show()
            }
    }

    fun showWechatQr(context: Context) {
        val dp = { dp: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics).toInt() }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24f), dp(20f), dp(24f), dp(20f))
        }

        TextView(context).apply {
            text = context.getString(R.string.donate_wechat_scan)
            textSize = 15f
            setTextColor(Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            container.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(16f) })
        }

        val qrImageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            // Set the WeChat QR drawable — add a PNG named ic_wechat_qr.png to res/drawable/
            val resId = context.resources.getIdentifier("ic_wechat_qr", "drawable", context.packageName)
            if (resId != 0) {
                setImageResource(resId)
            } else {
                visibility = View.GONE
            }
        }
        container.addView(qrImageView, LinearLayout.LayoutParams(dp(200f), dp(200f)))

        val shape = GradientDrawable().apply {
            setColor("#F5F5F5".toColorInt())
            cornerRadius = dp(12f).toFloat()
        }

        AlertDialog.Builder(context)
            .setView(container)
            .create()
            .also {
                it.window?.setBackgroundDrawable(InsetDrawable(shape, dp(24f)))
                it.show()
            }
    }

    fun showEdgeXAuthorDialog(context: Context) {
        val dp = { dp: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics).toInt() }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(20f), dp(24f), dp(8f))
        }

        // Title
        TextView(context).apply {
            text = context.getString(R.string.donate_edgex_title)
            textSize = 18f
            setTextColor(Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
            root.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8f) })
        }

        // Subtitle
        TextView(context).apply {
            text = context.getString(R.string.donate_edgex_subtitle)
            textSize = 13f
            setTextColor("#666666".toColorInt())
            root.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(20f) })
        }

        // Buttons row (Alipay + WeChat + Ko-fi)
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val buttonParams = LinearLayout.LayoutParams(0, dp(44f), 1f).also {
            it.marginEnd = dp(4f)
        }

        // Alipay button
        val alipayBtn = makeButton(context, context.getString(R.string.donate_alipay), "#1677FF".toColorInt())
        buttonRow.addView(alipayBtn, buttonParams)

        // WeChat button
        val wechatParams = LinearLayout.LayoutParams(0, dp(44f), 1f).also {
            it.marginStart = dp(4f)
            it.marginEnd = dp(4f)
        }
        val wechatBtn = makeButton(context, context.getString(R.string.donate_wechat), "#07C160".toColorInt())
        buttonRow.addView(wechatBtn, wechatParams)

        // Ko-fi button
        val kofiParams = LinearLayout.LayoutParams(0, dp(44f), 1f).also {
            it.marginStart = dp(4f)
        }
        val kofiBtn = makeButton(context, context.getString(R.string.donate_edgex_kofi), "#13C3FF".toColorInt())
        buttonRow.addView(kofiBtn, kofiParams)

        root.addView(buttonRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(4f) })

        val shape = GradientDrawable().apply {
            setColor("#F5F5F5".toColorInt())
            cornerRadius = dp(12f).toFloat()
        }
        val bg = InsetDrawable(shape, dp(24f))

        val dialog = AlertDialog.Builder(context)
            .setView(root)
            .create()

        dialog.window?.setBackgroundDrawable(bg)

        alipayBtn.setOnClickListener {
            dialog.dismiss()
            showEdgeXAlipayQr(context)
        }
        wechatBtn.setOnClickListener {
            dialog.dismiss()
            showEdgeXWechatQr(context)
        }
        kofiBtn.setOnClickListener {
            dialog.dismiss()
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(EDGEX_KOFI_URL)))
        }

        dialog.show()
    }

    private fun showEdgeXAlipayQr(context: Context) {
        val dp = { dp: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics).toInt() }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24f), dp(20f), dp(24f), dp(20f))
        }

        TextView(context).apply {
            text = context.getString(R.string.donate_edgex_alipay_scan)
            textSize = 15f
            setTextColor(Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            container.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(16f) })
        }

        val qrImageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val resId = context.resources.getIdentifier("ic_alipay_qr_edgex", "drawable", context.packageName)
            if (resId != 0) setImageResource(resId) else visibility = View.GONE
        }
        container.addView(qrImageView, LinearLayout.LayoutParams(dp(200f), dp(200f)))

        val shape = GradientDrawable().apply {
            setColor("#F5F5F5".toColorInt())
            cornerRadius = dp(12f).toFloat()
        }

        AlertDialog.Builder(context)
            .setView(container)
            .create()
            .also {
                it.window?.setBackgroundDrawable(InsetDrawable(shape, dp(24f)))
                it.show()
            }
    }

    private fun showEdgeXWechatQr(context: Context) {
        val dp = { dp: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics).toInt() }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24f), dp(20f), dp(24f), dp(20f))
        }

        TextView(context).apply {
            text = context.getString(R.string.donate_edgex_wechat_scan)
            textSize = 15f
            setTextColor(Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            container.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(16f) })
        }

        val qrImageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val resId = context.resources.getIdentifier("ic_wechat_qr_edgex", "drawable", context.packageName)
            if (resId != 0) setImageResource(resId) else visibility = View.GONE
        }
        container.addView(qrImageView, LinearLayout.LayoutParams(dp(200f), dp(200f)))

        val shape = GradientDrawable().apply {
            setColor("#F5F5F5".toColorInt())
            cornerRadius = dp(12f).toFloat()
        }

        AlertDialog.Builder(context)
            .setView(container)
            .create()
            .also {
                it.window?.setBackgroundDrawable(InsetDrawable(shape, dp(24f)))
                it.show()
            }
    }
}
