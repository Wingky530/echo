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
    
    // Inject ViewModel
    private val vm: ListenTogetherViewModel by activityViewModel()
    private val playerVm: PlayerViewModel by activityViewModel()

    // Variabel Anti-Spam
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
            lastTrackId = null // Reset memori lagu pas keluar
        }

        // --- PEREKAT LOGIKA SUARA & ANTI-SPAM ---
        viewLifecycleOwner.lifecycleScope.launch {
            vm.syncEvent.collect { event ->
                val state = vm.state.value as? ListenTogetherState.Active
                if (state != null && !state.isHost) {
                    val extId = event.extensionId ?: arguments?.getString("extensionId") ?: return@collect
                    
                    // Cek: Apakah lagu yang dikirim Host BERBEDA dengan yang sedang kita dengar?
                    if (lastTrackId != event.trackId) {
                        lastTrackId = event.trackId
                        
                        // Bungkus jadi Track sementara (nanti Echo akan download info aslinya)
                        val track = Track(id = event.trackId, title = "Listen Together Session")
                        
                        // Mainkan lagunya! (false = minta Echo fetch audio aslinya pakai extId)
                        playerVm.play(extId, track, false)
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
