package com.mrc.warehouse.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mrc.warehouse.api.BalanceItem
import com.mrc.warehouse.databinding.ItemBalanceBinding

class BalancesAdapter(
    private var items: List<BalanceItem>
) : RecyclerView.Adapter<BalancesAdapter.BalanceViewHolder>() {

    fun updateData(newItems: List<BalanceItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BalanceViewHolder {
        val binding = ItemBalanceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return BalanceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BalanceViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class BalanceViewHolder(private val binding: ItemBalanceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: BalanceItem) {
            binding.tvProductName.text = item.productName ?: "Без названия"
            binding.tvSeriesNumber.text = "Серия: ${item.seriesName ?: "—"}"
            binding.tvInventoryNumber.text = "Инв. номер: ${item.inventoryNumber ?: "—"}"
            binding.tvBalance.text = item.balance?.toString() ?: "0"
        }
    }
}