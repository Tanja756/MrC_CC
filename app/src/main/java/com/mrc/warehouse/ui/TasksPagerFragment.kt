package com.mrc.warehouse.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.mrc.warehouse.databinding.FragmentTasksPagerBinding

class TasksPagerFragment : Fragment() {

    private var _binding: FragmentTasksPagerBinding? = null
    private val binding get() = _binding!!

    private lateinit var pagerAdapter: TasksPagerAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTasksPagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pagerAdapter = TasksPagerAdapter(requireActivity())
        binding.viewPager.adapter = pagerAdapter

        // Restore initial page from saved instance or default to 0
        val initialPage = arguments?.getInt("pageIndex", 0) ?: 0

        // Set up page change callback
        binding.viewPager.registerOnPageChangeCallback(object :
            androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateUiForPage(position)
            }
        })

        // Left arrow click
        binding.btnPagePrev.setOnClickListener {
            val current = binding.viewPager.currentItem
            if (current > 0) {
                binding.viewPager.currentItem = current - 1
            }
        }

        // Right arrow click
        binding.btnPageNext.setOnClickListener {
            val current = binding.viewPager.currentItem
            if (current < TasksPagerAdapter.PAGE_COUNT - 1) {
                binding.viewPager.currentItem = current + 1
            }
        }

        // Set initial page (must be done after callback registration)
        binding.viewPager.setCurrentItem(initialPage, false)
        updateUiForPage(initialPage)
    }

    private fun updateUiForPage(position: Int) {
        // Update title
        binding.tvPageTitle.text = TasksPagerAdapter.pageTitles[position]

        // Enable/disable arrows at boundaries
        binding.btnPagePrev.isEnabled = position > 0
        binding.btnPageNext.isEnabled = position < TasksPagerAdapter.PAGE_COUNT - 1

        // Visual feedback for disabled state
        val disabledAlpha = 0.3f
        val enabledAlpha = 1.0f
        binding.btnPagePrev.alpha = if (position > 0) enabledAlpha else disabledAlpha
        binding.btnPageNext.alpha = if (position < TasksPagerAdapter.PAGE_COUNT - 1) enabledAlpha else disabledAlpha
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_PAGE_INDEX = "pageIndex"

        fun newInstance(pageIndex: Int): TasksPagerFragment {
            val fragment = TasksPagerFragment()
            val args = Bundle()
            args.putInt(ARG_PAGE_INDEX, pageIndex)
            fragment.arguments = args
            return fragment
        }
    }
}