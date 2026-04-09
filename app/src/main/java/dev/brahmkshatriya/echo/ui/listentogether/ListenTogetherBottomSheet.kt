package dev.brahmkshatriya.echo.ui.listentogether

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.databinding.BottomSheetListenTogetherBinding
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import kotlin.math.abs

class ListenTogetherBottomSheet : BottomSheetDialogFragment() {
    companion object {
        const val TAG = "ListenTogetherBottomSheet"
        fun newInstance(extensionId: String, trackId: String?) = ListenTogetherBottomSheet().apply {
            arguments = Bundle().apply {
                putString("extensionId", extensionId)
                putString("trackId", trackId)
            }
        }
        fun show(fm: FragmentManager, extensionId: String, trackId: String?) = newInstance(extensionId, trackId).show(fm, TAG)
    }

    private var _binding: BottomSheetListenTogetherBinding? = null
    private val binding get() = _binding!!
    
    private val vm: ListenTogetherViewModel by activityViewModel()
    private val playerVm: PlayerViewModel by activityViewModel()

    private var lastTrackId: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetListenTogetherBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observe(vm.state) { renderState(it) }

        binding.btnCreate.setOnClickListener { vm.createSession(arguments?.getString("trackId"), arguments?.getString("extensionId")) }
        binding.btnJoin.setOnClickListener {
            val code = binding.etCode.text?.toString()?.trim()
            if (!code.isNullOrBlank() && code.length >= 6) vm.joinSession(code)
        }
        binding.btnLeave.setOnClickListener { 
            vm.leaveSession()
            lastTrackId = null
        }

        // --- TRUE SYNC LOGIC ---
        viewLifecycleOwner.lifecycleScope.launch {
            vm.syncEvent.collect { event ->
                val state = vm.state.value as? ListenTogetherState.Active
                if (state != null && !state.isHost) {
                    val extId = event.extensionId ?: arguments?.getString("extensionId") ?: return@collect
                    
                    // 1. SINKRONISASI LAGU (Hanya jika ID berbeda)
                    if (lastTrackId != event.trackId) {
                        lastTrackId = event.trackId
                        val track = Track(id = event.trackId, title = "Listen Together Session")
                        playerVm.play(extId, track, false)
                    }

                    // 2. SINKRONISASI STATUS PLAY/PAUSE (Hanya jika berbeda)
                    val localIsPlaying = playerVm.playbackState.value?.isPlaying ?: false
                    if (localIsPlaying != event.isPlaying) {
                        playerVm.setPlaying(event.isPlaying)
                    }

                    // 3. SINKRONISASI POSISI (Cek Drift / Selisih Nyata)
                    // Kita bandingkan posisi player lokal dengan posisi Host dari Firebase
                    val localPos = playerVm.playbackState.value?.position ?: 0L
                    val drift = abs(localPos - event.positionMs)

                    // Jika selisih lebih dari 5 detik, baru kita paksa lompat (Seek)
                    // Ini menghilangkan masalah patah-patah tiap 3 detik.
                    if (drift > 5000) {
                        playerVm.seekTo(event.positionMs)
                    }
                }
            }
        }
    }

    private fun renderState(state: ListenTogetherState) {
        binding.panelSetup.isVisible = state is ListenTogetherState.Idle
        binding.panelActive.isVisible = state is ListenTogetherState.Active
        if (state is ListenTogetherState.Active) {
            binding.tvSessionCode.text = state.sessionCode
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
