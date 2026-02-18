package com.vltv.play.ui.downloads

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.vltv.play.databinding.FragmentDownloadsBinding

class DownloadsFragment : Fragment() {
    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // O erro Unresolved Reference acontece aqui se o XML não tiver a tag <layout>
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
