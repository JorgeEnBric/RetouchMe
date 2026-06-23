package com.example.retake_lite.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.retake_lite.databinding.FragmentHomeBinding
import com.example.retake_lite.ui.profile.ProfileManagerActivity
import com.example.retake_lite.ui.swap.FaceSwapActivity

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)

        binding.btnOpenProfiles.setOnClickListener {
            startActivity(Intent(requireContext(), ProfileManagerActivity::class.java))
        }
        binding.btnOpenFaceSwap.setOnClickListener {
            startActivity(Intent(requireContext(), FaceSwapActivity::class.java))
        }

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
