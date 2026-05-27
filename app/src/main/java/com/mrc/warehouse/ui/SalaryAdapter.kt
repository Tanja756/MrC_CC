package com.mrc.warehouse.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.mrc.warehouse.R
import com.mrc.warehouse.api.SalaryItem
import com.mrc.warehouse.databinding.ItemSalaryBinding
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*

class SalaryAdapter(
    private var items: List<SalaryItem>,
    private var totalAmount: Double = 0.0
) : RecyclerView.Adapter<SalaryAdapter.SalaryViewHolder>() {

    private val formatter = DecimalFormat("#,###", DecimalFormatSymbols(Locale.US).apply {
        groupingSeparator = ' '
    })

    fun updateData(newItems: List<SalaryItem>, newTotal: Double) {
        items = newItems
        totalAmount = newTotal
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size + 1 // +1 for total row

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SalaryViewHolder {
        val binding = ItemSalaryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SalaryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SalaryViewHolder, position: Int) {
        val context = holder.itemView.context
        if (position < items.size) {
            val item = items[position]
            holder.binding.tvTitle.text = item.title ?: "—"
            holder.binding.tvValue.text = "${formatter.format((item.value ?: 0.0).toLong())} руб."

            // Alternating row backgrounds
            val bgColor = if (position % 2 == 0) {
                ContextCompat.getColor(context, android.R.color.white)
            } else {
                ContextCompat.getColor(context, R.color.bg_main)
            }
            holder.itemView.setBackgroundColor(bgColor)
            holder.binding.tvTitle.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            holder.binding.tvValue.setTextColor(ContextCompat.getColor(context, R.color.primary_dark))
            holder.binding.dividerRight.visibility = android.view.View.GONE
        } else {
            // Total row
            holder.binding.tvTitle.text = "Итого"
            holder.binding.tvValue.text = "${formatter.format(totalAmount.toLong())} руб."

            holder.itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.primary_surface))
            holder.binding.tvTitle.setTextColor(ContextCompat.getColor(context, R.color.primary_dark))
            holder.binding.tvTitle.isEnabled = false
            holder.binding.tvValue.setTextColor(ContextCompat.getColor(context, R.color.primary_dark))
            holder.binding.tvValue.textSize = 15f
            holder.binding.dividerRight.visibility = android.view.View.VISIBLE
        }
    }

    inner class SalaryViewHolder(val binding: ItemSalaryBinding) :
        RecyclerView.ViewHolder(binding.root)
}