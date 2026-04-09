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
import dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension.Companion.EXTENSION_ID
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import kotlinx.coroutines.delay
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

    private var lastBroadcastTrackId: String? = null
    private var lastBroadcastIsPlaying: Boolean? = null
    private var lastListenerTrackId: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetListenTogetherBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        observe(vm.state) { state -> 
            renderState(state) 
            
            // ✅ FIX 1: Host otomatis nyetor lagu pas baru banget bikin room
            if (state is ListenTogetherState.Active && state.isHost && lastBroadcastTrackId == null) {
                val current = playerVm.playerState.current.value ?: return@observe
                val track = current.track ?: return@observe
                val extId = track.extras[EXTENSION_ID] ?: arguments?.getString("extensionId") ?: ""
                val positionMs = playerVm.browser.value?.currentPosition ?: 0L
                
                vm.broadcastSync(track.id, extId, positionMs, current.isPlaying)
                lastBroadcastTrackId = track.id
                lastBroadcastIsPlaying = current.isPlaying
            }
        }

        // =============================================
        // HOST: Broadcast saat track berubah
        // =============================================
        observe(playerVm.playerState.current) { current ->
            val s = vm.state.value as? ListenTogetherState.Active ?: return@observe
            if (!s.isHost) return@observe
            val track = current?.track ?: return@observe
            // ✅ FIX 2: Jangan return kalau null, jadikan string kosong
            val extId = track.extras[EXTENSION_ID] ?: arguments?.getString("extensionId") ?: ""
            val positionMs = playerVm.browser.value?.currentPosition ?: 0L

            vm.broadcastSync(track.id, extId, positionMs, current.isPlaying)
            lastBroadcastTrackId = track.id
            lastBroadcastIsPlaying = current.isPlaying
        }

        // =============================================
        // HOST: Broadcast saat play/pause berubah
        // =============================================
        observe(playerVm.isPlaying) { isPlaying ->
            val s = vm.state.value as? ListenTogetherState.Active ?: return@observe
            if (!s.isHost) return@observe
            if (lastBroadcastIsPlaying == isPlaying) return@observe
            val current = playerVm.playerState.current.value ?: return@observe
            val track = current.track ?: return@observe
            val extId = track.extras[EXTENSION_ID] ?: arguments?.getString("extensionId") ?: ""
            val positionMs = playerVm.browser.value?.currentPosition ?: 0L

            vm.broadcastSync(track.id, extId, positionMs, isPlaying)
            lastBroadcastIsPlaying = isPlaying
        }

        // =============================================
        // LISTENER: Terima sync dari host
        // =============================================
        viewLifecycleOwner.lifecycleScope.launch {
            vm.syncEvent.collect { event ->
                val s = vm.state.value as? ListenTogetherState.Active ?: return@collect
                if (s.isHost) return@collect
                val extId = event.extensionId ?: arguments?.getString("extensionId") ?: ""

                // 1. Ganti lagu jika berbeda
                if (lastListenerTrackId != event.trackId) {
                    lastListenerTrackId = event.trackId
                    val track = Track(id = event.trackId, title = "Listen Together", extras = mapOf(EXTENSION_ID to extId))
                    playerVm.play(extId, track, false)
                    
                    // ✅ FIX 3: Kasih jeda 500ms biar ExoPlayer siapin lagu sblm di seek.
                    delay(500) 
                }

                // 2. Kalkulasi Kompensasi Latency
                val networkLatency = System.currentTimeMillis() - event.timestamp
                var expectedPosition = event.positionMs
                if (event.isPlaying) { expectedPosition += networkLatency }

                // 3. Sync posisi — toleransi 2.5 detik
                val localPos = playerVm.browser.value?.currentPosition ?: 0L
                if (abs(localPos - expectedPosition) > 2500) {
                    playerVm.seekTo(expectedPosition)
                }

                // 4. Sync play/pause
                val localIsPlaying = playerVm.playerState.current.value?.isPlaying ?: false
                if (localIsPlaying != event.isPlaying) {
                    playerVm.setPlaying(event.isPlaying)
                }
            }
        }

        binding.btnCreate.setOnClickListener { vm.createSession(arguments?.getString("trackId"), arguments?.getString("extensionId")) }
        binding.btnJoin.setOnClickListener {
            val code = binding.etCode.text?.toString()?.trim()
            if (!code.isNullOrBlank() && code.length >= 6) {
                lastListenerTrackId = null
                vm.joinSession(code)
            } else { binding.etCode.error = getString(R.string.listen_together_code_hint) }
        }
        binding.btnCopy.setOnClickListener {
            val s = vm.state.value as? ListenTogetherState.Active ?: return@setOnClickListener
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("code", s.sessionCode))
            Toast.makeText(context, R.string.listen_together_copied, Toast.LENGTH_SHORT).show()
        }
        binding.btnLeave.setOnClickListener {
            vm.leaveSession()
            lastBroadcastTrackId = null
            lastBroadcastIsPlaying = null
            lastListenerTrackId = null
        }
    }

    private fun renderState(state: ListenTogetherState) {
        binding.panelSetup.isVisible = state is ListenTogetherState.Idle || state is ListenTogetherState.Error
        binding.progressConnecting.isVisible = state is ListenTogetherState.Connecting
        binding.panelActive.isVisible = state is ListenTogetherState.Active
        if (state is ListenTogetherState.Active) {
            binding.tvSessionCode.text = state.sessionCode
            binding.tvRole.text = if (state.isHost) getString(R.string.listen_together_you_host) else getString(R.string.listen_together_listening_with)
            binding.tvParticipants.text = resources.getQuantityString(R.plurals.listen_together_participants, state.participants.size, state.participants.size)
            binding.btnCopy.isVisible = state.isHost
        }
        if (state is ListenTogetherState.Error) { Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show() }
    }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
