package com.georgeb.retouchme.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.georgeb.retouchme.data.FaceRepository
import com.georgeb.retouchme.databinding.FragmentHomeBinding
import com.georgeb.retouchme.ui.profile.ProfileManagerActivity
import com.georgeb.retouchme.ui.swap.FaceSwapActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private var profileObserver: Job? = null

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

    override fun onResume() {
        super.onResume()
        val repository = FaceRepository(requireContext())
        profileObserver = viewLifecycleOwner.lifecycleScope.launch {
            repository.getAllProfiles().collectLatest { profiles ->
                binding.btnOpenFaceSwap.isEnabled = profiles.isNotEmpty()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        profileObserver?.cancel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
