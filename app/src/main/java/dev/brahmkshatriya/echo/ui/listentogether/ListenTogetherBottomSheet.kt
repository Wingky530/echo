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
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.databinding.BottomSheetListenTogetherBinding
import dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension.Companion.EXTENSION_ID
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import kotlinx.coroutines.launch
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
    private val playerVm: PlayerViewModel by activityViewModel()

    private val extensionId by lazy { arguments?.getString("extensionId") ?: "" }
    private val trackId by lazy { arguments?.getString("trackId") }

    // Cegah broadcast loop
    private var isSyncing = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetListenTogetherBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        observe(vm.state) { renderState(it) }

        // CLIENT: terima sync dari host
        viewLifecycleOwner.lifecycleScope.launch {
            vm.syncEvent.collect { event ->
                isSyncing = true
                applySyncToPlayer(event)
                isSyncing = false
            }
        }

        // HOST: broadcast saat track berubah
        observe(playerVm.playerState.current) { current ->
            if (isSyncing) return@observe
            val s = vm.state.value as? ListenTogetherState.Active ?: return@observe
            if (!s.isHost) return@observe
            val track = current?.track ?: return@observe
            val extId = track.extras[EXTENSION_ID] ?: extensionId
            vm.broadcastSync(
                trackId = track.id,
                extensionId = extId,
                positionMs = 0L,
                isPlaying = current.isPlaying
            )
        }

        // HOST: broadcast saat play/pause berubah
        observe(playerVm.isPlaying) { isPlaying ->
            if (isSyncing) return@observe
            val s = vm.state.value as? ListenTogetherState.Active ?: return@observe
            if (!s.isHost) return@observe
            val current = playerVm.playerState.current.value ?: return@observe
            val track = current.track
            val extId = track.extras[EXTENSION_ID] ?: extensionId
            vm.broadcastSync(
                trackId = track.id,
                extensionId = extId,
                positionMs = 0L,
                isPlaying = isPlaying
            )
        }

        binding.btnCreate.setOnClickListener {
            vm.createSession(trackId, extensionId)
        }

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
            val clipboard =
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Session Code", s.sessionCode))
            Toast.makeText(context, R.string.listen_together_copied, Toast.LENGTH_SHORT).show()
        }

        binding.btnLeave.setOnClickListener {
            vm.leaveSession()
        }
    }

    private fun applySyncToPlayer(event: SyncEvent) {
        val current = playerVm.playerState.current.value
        val currentTrackId = current?.track?.id
        val currentExtId = current?.track?.extras?.get(EXTENSION_ID)

        // Load track baru jika berbeda
        if (currentTrackId != event.trackId) {
            val extId = event.extensionId ?: extensionId
            val track = Track(
                id = event.trackId,
                title = "",
                extras = mapOf(EXTENSION_ID to extId)
            )
            playerVm.play(extId, track, false)
            // Setelah play, seek ke posisi host
            if (event.positionMs > 0) {
                playerVm.seekTo(event.positionMs)
            }
            return
        }

        // Track sama → hanya sync posisi & play state
        if (event.positionMs > 0) {
            playerVm.seekTo(event.positionMs)
        }

        val currentlyPlaying = current?.isPlaying ?: false
        if (currentlyPlaying != event.isPlaying) {
            playerVm.setPlaying(event.isPlaying)
        }
    }

    private fun renderState(state: ListenTogetherState) {
        binding.panelSetup.isVisible =
            state is ListenTogetherState.Idle || state is ListenTogetherState.Error
        binding.progressConnecting.isVisible = state is ListenTogetherState.Connecting
        binding.panelActive.isVisible = state is ListenTogetherState.Active

        if (state is ListenTogetherState.Active) {
            binding.tvSessionCode.text = state.sessionCode
            binding.tvRole.text = if (state.isHost)
                getString(R.string.listen_together_you_host)
            else
                getString(R.string.listen_together_listening_with)
            binding.tvParticipants.text = resources.getQuantityString(
                R.plurals.listen_together_participants,
                state.participants.size,
                state.participants.size
            )
            binding.btnCopy.isVisible = state.isHost
        }

        if (state is ListenTogetherState.Error) {
            Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
