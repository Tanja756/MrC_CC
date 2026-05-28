package com.mrc.warehouse.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.mrc.warehouse.R

class TasksSheetFragment : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_tasks_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val navController = findNavController()

        view.findViewById<View>(R.id.layoutMyTasks).setOnClickListener {
            val bundle = Bundle().apply { putInt("pageIndex", 0) }
            navController.navigate(R.id.navigation_tasks, bundle)
            dismiss()
        }

        view.findViewById<View>(R.id.layoutFreeTasks).setOnClickListener {
            val bundle = Bundle().apply { putInt("pageIndex", 1) }
            navController.navigate(R.id.navigation_tasks, bundle)
            dismiss()
        }

        view.findViewById<View>(R.id.layoutClosedTasks).setOnClickListener {
            val bundle = Bundle().apply { putInt("pageIndex", 2) }
            navController.navigate(R.id.navigation_tasks, bundle)
            dismiss()
        }
    }
}