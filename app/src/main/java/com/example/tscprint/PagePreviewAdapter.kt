package com.example.tscprint

import android.graphics.Bitmap
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as MaterialR
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors

class PagePreviewAdapter(
    private val onToggle: (Int) -> Unit
) : RecyclerView.Adapter<PagePreviewAdapter.PageHolder>() {

    data class Item(val index: Int, val bitmap: Bitmap, val selected: Boolean)

    private var items: List<Item> = emptyList()

    fun submit(items: List<Item>) {
        this.items = items
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val density = parent.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val card = MaterialCardView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(dp(132), dp(214)).also {
                it.marginEnd = dp(8)
            }
            radius = dp(12).toFloat()
            cardElevation = 0f
            isClickable = true
            isFocusable = true
        }
        val column = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        val image = ImageView(parent.context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(166)
            )
        }
        val footer = LinearLayout(parent.context).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(parent.context).apply {
            setTextAppearance(MaterialR.style.TextAppearance_Material3_LabelLarge)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val check = MaterialCheckBox(parent.context).apply {
            val checkedColor = MaterialColors.getColor(card, MaterialR.attr.colorPrimary, 0)
            val uncheckedColor = MaterialColors.getColor(card, MaterialR.attr.colorOnSurfaceVariant, 0)
            buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(checkedColor, uncheckedColor)
            )
            contentDescription = "Select page"
        }
        footer.addView(label)
        footer.addView(check)
        column.addView(image)
        column.addView(footer)
        card.addView(column)
        return PageHolder(card, image, label, check)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val item = items[position]
        holder.image.setImageBitmap(item.bitmap)
        holder.label.text = "${item.index + 1}"
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = item.selected
        holder.check.contentDescription = "Select page ${item.index + 1}"
        holder.check.setOnCheckedChangeListener { _, _ -> onToggle(item.index) }
        holder.card.setOnClickListener { onToggle(item.index) }
        holder.card.strokeWidth = if (item.selected) 4 else 1
        holder.card.strokeColor = MaterialColors.getColor(
            holder.card,
            if (item.selected) MaterialR.attr.colorPrimary else MaterialR.attr.colorOutline,
            0
        )
    }

    override fun getItemCount(): Int = items.size

    class PageHolder(
        val card: MaterialCardView,
        val image: ImageView,
        val label: TextView,
        val check: MaterialCheckBox
    ) : RecyclerView.ViewHolder(card)
}
