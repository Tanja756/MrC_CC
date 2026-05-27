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

        view.findViewById<View>(R.id.layoutMyTasks).setOnClickListener {
            findNavController().navigate(R.id.navigation_tasks)
            dismiss()
        }

        view.findViewById<View>(R.id.layoutFreeTasks).setOnClickListener {
            findNavController().navigate(R.id.navigation_free_tasks)
            dismiss()
        }

        view.findViewById<View>(R.id.layoutClosedTasks).setOnClickListener {
            findNavController().navigate(R.id.navigation_closed_tasks)
            dismiss()
        }
    }
}