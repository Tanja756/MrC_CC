package com.mrc.warehouse.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class TasksPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {

    companion object {
        const val PAGE_MY_TASKS = 0
        const val PAGE_FREE_TASKS = 1
        const val PAGE_CLOSED_TASKS = 2
        const val PAGE_COUNT = 3

        val pageTitles = arrayOf(
            "Мои заявки",
            "Свободные заявки",
            "Закрытые заявки"
        )
    }

    override fun getItemCount(): Int = PAGE_COUNT

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            PAGE_MY_TASKS -> TasksFragment()
            PAGE_FREE_TASKS -> FreeTasksFragment()
            PAGE_CLOSED_TASKS -> ClosedTasksFragment()
            else -> TasksFragment()
        }
    }
}