package com.vexa.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

data class ChatMessage(
    val text: String,
    val sender: Sender,
    val time: String,
    val image: Bitmap? = null,
    val generatedFile: FileGenerator.GeneratedFile? = null
) {
    enum class Sender { USER, VEXA, SYSTEM }
}

class ChatAdapter(private val items: MutableList<ChatMessage>) : RecyclerView.Adapter<ChatAdapter.Holder>() {

    class Holder(
        val wrapper: FrameLayout,
        val card: LinearLayout,
        val headerView: TextView,
        val imageView: ImageView,
        val saveImgBtn: Button,
        val bodyView: TextView,
        val fileActionRow: LinearLayout,
        val openFileBtn: Button,
        val shareFileBtn: Button,
        val timeView: TextView
    ) : RecyclerView.ViewHolder(wrapper)

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun pillBg(ctx: Context, fillHex: String, strokeHex: String): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor(fillHex))
        cornerRadius = dp(ctx, 10).toFloat()
        setStroke(dp(ctx, 1), Color.parseColor(strokeHex))
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val wrapper = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(ctx, 5)
                bottomMargin = dp(ctx, 5)
            }
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 8))
            elevation = dp(ctx, 2).toFloat()
        }
        val headerView = TextView(ctx).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(ctx, 3))
        }
        val imageView = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 240), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(ctx, 4)
                bottomMargin = dp(ctx, 6)
            }
        }
        val saveImgBtn = Button(ctx).apply {
            text = "⬇️ ذخیره در گالری / Save"
            textSize = 11f
            setTextColor(Color.parseColor("#E0F2FE"))
            background = pillBg(ctx, "#0E7490", "#22D3EE")
            setPadding(dp(ctx, 12), dp(ctx, 4), dp(ctx, 12), dp(ctx, 4))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 34)).apply {
                bottomMargin = dp(ctx, 6)
            }
        }
        val bodyView = TextView(ctx).apply {
            textSize = 15f
            setLineSpacing(0f, 1.3f)
        }
        val fileActionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 2))
        }
        val openFileBtn = Button(ctx).apply {
            text = "📂 باز کردن / Open"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = pillBg(ctx, "#0891B2", "#22D3EE")
            setPadding(dp(ctx, 10), dp(ctx, 2), dp(ctx, 10), dp(ctx, 2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 34)).apply {
                marginEnd = dp(ctx, 8)
            }
        }
        val shareFileBtn = Button(ctx).apply {
            text = "📤 اشتراک / Share"
            textSize = 11f
            setTextColor(Color.parseColor("#CBD5E1"))
            background = pillBg(ctx, "#1E293B", "#334155")
            setPadding(dp(ctx, 10), dp(ctx, 2), dp(ctx, 10), dp(ctx, 2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 34))
        }
        fileActionRow.addView(openFileBtn)
        fileActionRow.addView(shareFileBtn)

        val timeView = TextView(ctx).apply {
            textSize = 10f
            setPadding(0, dp(ctx, 4), 0, 0)
            gravity = Gravity.END
        }

        card.addView(headerView)
        card.addView(imageView)
        card.addView(saveImgBtn)
        card.addView(bodyView)
        card.addView(fileActionRow)
        card.addView(timeView)
        wrapper.addView(card)

        return Holder(wrapper, card, headerView, imageView, saveImgBtn, bodyView, fileActionRow, openFileBtn, shareFileBtn, timeView)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val ctx = holder.wrapper.context
        val maxW = (ctx.resources.displayMetrics.widthPixels * 0.84f).toInt()
        val isFa = VexaCoreClient.isPersianText(item.text)

        val bg = GradientDrawable()
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        when (item.sender) {
            ChatMessage.Sender.USER -> {
                lp.gravity = Gravity.END
                lp.marginStart = dp(ctx, 28)
                bg.setColor(Color.parseColor("#0E7490"))
                bg.setStroke(dp(ctx, 1), Color.parseColor("#22D3EE"))
                bg.cornerRadius = dp(ctx, 16).toFloat()
                holder.headerView.text = if (isFa) "شما" else "You"
                holder.headerView.setTextColor(Color.parseColor("#CFFAFE"))
                holder.bodyView.setTextColor(Color.WHITE)
                holder.bodyView.maxWidth = maxW
                holder.timeView.setTextColor(Color.parseColor("#A5F3FC"))
            }
            ChatMessage.Sender.VEXA -> {
                lp.gravity = Gravity.START
                lp.marginEnd = dp(ctx, 28)
                bg.setColor(Color.parseColor("#0F172A"))
                bg.setStroke(dp(ctx, 1), Color.parseColor("#1E293B"))
                bg.cornerRadius = dp(ctx, 16).toFloat()
                holder.headerView.text = "VEXA AI"
                holder.headerView.setTextColor(Color.parseColor("#22D3EE"))
                holder.bodyView.setTextColor(Color.parseColor("#F1F5F9"))
                holder.bodyView.maxWidth = maxW
                holder.timeView.setTextColor(Color.parseColor("#64748B"))
            }
            ChatMessage.Sender.SYSTEM -> {
                lp.gravity = Gravity.CENTER_HORIZONTAL
                bg.setColor(Color.parseColor("#1E293B"))
                bg.setStroke(dp(ctx, 1), Color.parseColor("#334155"))
                bg.cornerRadius = dp(ctx, 12).toFloat()
                holder.headerView.text = "SYSTEM"
                holder.headerView.setTextColor(Color.parseColor("#FBBF24"))
                holder.bodyView.setTextColor(Color.parseColor("#CBD5E1"))
                holder.bodyView.maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.88f).toInt()
                holder.timeView.setTextColor(Color.parseColor("#64748B"))
            }
        }

        if (item.image != null) {
            holder.imageView.visibility = View.VISIBLE
            holder.imageView.setImageBitmap(item.image)
            if (item.sender == ChatMessage.Sender.VEXA) {
                holder.saveImgBtn.visibility = View.VISIBLE
                holder.saveImgBtn.setOnClickListener { saveBitmapToGallery(ctx, item.image) }
            } else {
                holder.saveImgBtn.visibility = View.GONE
            }
        } else {
            holder.imageView.visibility = View.GONE
            holder.saveImgBtn.visibility = View.GONE
            holder.imageView.setImageDrawable(null)
        }

        if (item.generatedFile != null) {
            holder.fileActionRow.visibility = View.VISIBLE
            holder.openFileBtn.setOnClickListener {
                if (!FileGenerator.openFile(ctx, item.generatedFile)) {
                    Toast.makeText(ctx, "برنامه‌ای برای باز کردن فایل یافت نشد", Toast.LENGTH_SHORT).show()
                }
            }
            holder.shareFileBtn.setOnClickListener {
                FileGenerator.shareFile(ctx, item.generatedFile)
            }
        } else {
            holder.fileActionRow.visibility = View.GONE
        }

        holder.card.layoutParams = lp
        holder.card.background = bg
        holder.bodyView.text = item.text
        holder.timeView.text = item.time

        holder.card.setOnLongClickListener {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Vexa Message", item.text))
            Toast.makeText(ctx, "کپی شد", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun saveBitmapToGallery(ctx: Context, bmp: Bitmap) {
        try {
            val name = "Vexa_AI_${System.currentTimeMillis()}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VexaAI")
                }
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                Toast.makeText(ctx, "در گالری (Pictures/VexaAI) ذخیره شد", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(ctx, "خطا در ذخیره تصویر", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount(): Int = items.size

    fun addMessage(msg: ChatMessage) {
        items.add(msg)
        notifyItemInserted(items.size - 1)
    }

    fun clearAll() {
        val count = items.size
        items.clear()
        notifyItemRangeRemoved(0, count)
    }
}
