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
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.databinding.BottomSheetListenTogetherBinding
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
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

    private val vm: ListenTogetherViewModel by activityViewModel()
    private val playerVm: PlayerViewModel by activityViewModel()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetListenTogetherBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ✅ Kasih semua remot kontrol player ke ViewModel
        vm.playerState = playerVm.playerState
        vm.browserProvider = { playerVm.browser.value }
        vm.isPlayingProvider = { playerVm.isPlaying.value }
        vm.playAction = { extId, track, isLocal -> playerVm.play(extId, track, isLocal) }
        vm.seekAction = { pos -> playerVm.seekTo(pos) }
        vm.setPlayingAction = { isPlaying -> playerVm.setPlaying(isPlaying) }

        observe(vm.state) { renderState(it) }

        binding.btnCreate.setOnClickListener {
            vm.createSession(arguments?.getString("trackId"), arguments?.getString("extensionId"))
        }

        binding.btnJoin.setOnClickListener {
            val code = binding.etCode.text?.toString()?.trim()
            if (!code.isNullOrBlank() && code.length >= 6) {
                vm.joinSession(code)
            } else {
                binding.etCode.error = getString(R.string.listen_together_code_hint)
            }
        }

        binding.btnCopy.setOnClickListener {
            val s = vm.state.value as? ListenTogetherState.Active ?: return@setOnClickListener
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("code", s.sessionCode))
            Toast.makeText(context, R.string.listen_together_copied, Toast.LENGTH_SHORT).show()
        }

        binding.btnLeave.setOnClickListener {
            vm.leaveSession()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
