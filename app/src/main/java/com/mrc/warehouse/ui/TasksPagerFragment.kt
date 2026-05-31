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
    private var currentFragmentCallback: SearchSortCallback? = null

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

        val initialPage = arguments?.getInt("pageIndex", 0) ?: 0

        binding.viewPager.registerOnPageChangeCallback(object :
            androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateUiForPage(position)
                // Обновляем callback для текущего фрагмента
                currentFragmentCallback = (pagerAdapter.getFragment(position) as? SearchSortCallback)
            }
        })

        binding.btnSearchToggle.setOnClickListener {
            currentFragmentCallback?.onSearchToggle()
        }

        binding.btnFilterToggle.setOnClickListener {
            currentFragmentCallback?.onSortToggle()
        }

        binding.viewPager.setCurrentItem(initialPage, false)
        updateUiForPage(initialPage)
        // Устанавливаем callback для начальной страницы
        currentFragmentCallback = (pagerAdapter.getFragment(initialPage) as? SearchSortCallback)
    }

    private fun updateUiForPage(position: Int) {
        binding.tvPageTitle.text = TasksPagerAdapter.pageTitles[position]
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