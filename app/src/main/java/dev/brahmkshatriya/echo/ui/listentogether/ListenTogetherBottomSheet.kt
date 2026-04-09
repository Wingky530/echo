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
import dev.brahmkshatriya.echo.databinding.BottomSheetListenTogetherBinding
import dev.brahmkshatriya.echo.playback.PlaybackConnection
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class ListenTogetherBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "ListenTogetherBottomSheet"
        fun newInstance(extensionId: String, trackId: String?) =
            ListenTogetherBottomSheet().apply {
                arguments = Bundle().apply {
                    putString("extensionId", extensionId)
                    putString("trackId", trackId)
                }
            }
        fun show(fm: FragmentManager, extensionId: String, trackId: String?) {
            newInstance(extensionId, trackId).show(fm, TAG)
        }
    }

    private var _binding: BottomSheetListenTogetherBinding? = null
    private val binding get() = _binding!!
    private val vm: ListenTogetherViewModel by activityViewModel()
    private val playbackConnection: PlaybackConnection by inject()

    private val extensionId by lazy { arguments?.getString("extensionId") ?: "" }
    private val trackId by lazy { arguments?.getString("trackId") }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetListenTogetherBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observe(vm.state) { state -> renderState(state) }

        // --- INI PEREKATNYA: Mendengarkan perintah sync ---
        viewLifecycleOwner.lifecycleScope.launch {
            vm.syncEvent.collect { event ->
                val state = vm.state.value
                if (state is ListenTogetherState.Active && !state.isHost) {
                    val currentTrack = playbackConnection.currentMetadata.value
                    
                    // 1. Load lagu jika berbeda
                    if (currentTrack?.id != event.trackId && event.extensionId != null) {
                        playbackConnection.playTrack(event.trackId, event.extensionId)
                    }

                    // 2. Sync Posisi jika selisih > 3 detik
                    val currentPos = playbackConnection.playbackState.value.position
                    if (Math.abs(currentPos - event.positionMs) > 3000) {
                        playbackConnection.seekTo(event.positionMs)
                    }

                    // 3. Sync Play/Pause
                    if (event.isPlaying) playbackConnection.play() else playbackConnection.pause()
                }
            }
        }

        binding.btnCreate.setOnClickListener { vm.createSession(trackId, extensionId) }
        binding.btnJoin.setOnClickListener {
            val code = binding.etCode.text?.toString()?.trim()
            if (code.isNullOrBlank() || code.length < 6) {
                binding.etCode.error = getString(R.string.listen_together_code_hint)
                return@setOnClickListener
            }
            vm.joinSession(code)
        }
        binding.btnCopy.setOnClickListener {
            val s = vm.state.value as? ListenTogetherState.Active ?: return@setOnClickListener
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Session Code", s.sessionCode))
            Toast.makeText(context, R.string.listen_together_copied, Toast.LENGTH_SHORT).show()
        }
        binding.btnLeave.setOnClickListener { vm.leaveSession() }
    }

    private fun renderState(state: ListenTogetherState) {
        binding.panelSetup.isVisible = state is ListenTogetherState.Idle || state is ListenTogetherState.Error
        binding.progressConnecting.isVisible = state is ListenTogetherState.Connecting
        binding.panelActive.isVisible = state is ListenTogetherState.Active

        if (state is ListenTogetherState.Active) {
            binding.tvSessionCode.text = state.sessionCode
            binding.tvRole.text = if (state.isHost) "Kamu adalah Host" else "Mendengarkan bersama"
            binding.tvParticipants.text = "${state.participants.size} pendengar"
            binding.btnCopy.isVisible = state.isHost
        }
        if (state is ListenTogetherState.Error) Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
