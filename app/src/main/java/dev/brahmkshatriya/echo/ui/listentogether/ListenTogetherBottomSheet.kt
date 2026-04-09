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
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
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
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Session Code", s.sessionCode))
            Toast.makeText(context, R.string.listen_together_copied, Toast.LENGTH_SHORT).show()
        }

        binding.btnLeave.setOnClickListener {
            vm.leaveSession()
        }
    }

    private fun renderState(state: ListenTogetherState) {
        val isIdle = state is ListenTogetherState.Idle
        val isConnecting = state is ListenTogetherState.Connecting
        val isActive = state is ListenTogetherState.Active
        val isError = state is ListenTogetherState.Error

        // Panel create/join
        binding.panelSetup.isVisible = isIdle || isError
        binding.progressConnecting.isVisible = isConnecting

        // Panel aktif
        binding.panelActive.isVisible = isActive

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
            binding.btnLeave.isVisible = true
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
