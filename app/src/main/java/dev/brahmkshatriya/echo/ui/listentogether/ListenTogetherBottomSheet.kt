package dev.brahmkshatriya.echo.ui.listentogether

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import coil.load
import android.content.Intent
import android.net.Uri
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.databinding.BottomSheetListenTogetherBinding
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.ui.extensions.login.LoginUserListViewModel
import kotlinx.coroutines.launch

class ListenTogetherBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetListenTogetherBinding? = null
    private val binding get() = _binding!!

    private val vm: ListenTogetherViewModel by activityViewModels()
    private val playerVm: PlayerViewModel by activityViewModels()
    private val loginVm: LoginUserListViewModel by activityViewModels()

    private val participantAdapter = ParticipantAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetListenTogetherBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vm.playerState = playerVm.playerState
        vm.browserProvider = { playerVm.browser.value }
        vm.isPlayingProvider = { playerVm.isPlaying.value }
        vm.playAction = { extId, track, isLocal -> playerVm.play(extId, track, isLocal) }
        vm.seekAction = { pos -> playerVm.seekTo(pos) }
        vm.setPlayingAction = { isPlaying -> playerVm.setPlaying(isPlaying) }

        binding.rvParticipants.adapter = participantAdapter
        
        viewLifecycleOwner.lifecycleScope.launch {
            vm.state.collect { renderState(it) }
        }

        binding.btnCreate.setOnClickListener {
            vm.createSession(arguments?.getString("trackId"), arguments?.getString("extensionId"), getActiveUsername(), getActiveAvatar())
        }

        binding.btnJoin.setOnClickListener {
            val code = binding.etCode.text?.toString()?.trim()
            if (!code.isNullOrBlank() && code.length >= 6) {
                vm.joinSession(code, getActiveUsername(), getActiveAvatar())
            } else {
                binding.etCode.error = getString(R.string.listen_together_code_hint)
            }
        }

        binding.btnCopy.setOnClickListener {
            val s = vm.state.value as? ListenTogetherState.Active ?: return@setOnClickListener
            val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("code", s.sessionCode))
            Toast.makeText(requireContext(), R.string.listen_together_copied, Toast.LENGTH_SHORT).show()
        }

        binding.btnLeave.setOnClickListener { vm.leaveSession() }
    }

    private fun getActiveUsername(): String {
        val loginName = loginVm.currentUser.value?.name
        if (!loginName.isNullOrBlank()) return loginName
        val customName = ListenTogetherSettingsFragment.getUsername(requireContext())
        return if (customName.isNotBlank()) customName else "Guest"
    }

    private fun getActiveAvatar(): String? {
        val cover = loginVm.currentUser.value?.cover ?: return null
        return when (cover) {
            is ImageHolder.NetworkRequestImageHolder -> cover.request.url
            is ImageHolder.ResourceUriImageHolder -> cover.uri
            else -> null
        }
    }

    private fun renderState(state: ListenTogetherState) {
        binding.panelSetup.isVisible = state is ListenTogetherState.Idle || state is ListenTogetherState.Error
        binding.progressConnecting.isVisible = state is ListenTogetherState.Connecting
        binding.panelActive.isVisible = state is ListenTogetherState.Active

        if (state is ListenTogetherState.Active) {
            binding.tvSessionCode.text = state.sessionCode
            binding.tvRole.text = if (state.isHost) getString(R.string.listen_together_you_host) else getString(R.string.listen_together_listening_with)
            binding.btnSettings.isVisible = state.isHost

            participantAdapter.updateData(state.participants.sortedWith(compareBy({ !it.isHost }, { it.name })))
            binding.tvParticipants.text = "Participants (${state.participants.size})"
        }

        if (state is ListenTogetherState.Error) Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
    }

    private fun showExtensionWarning(extId: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Extension Mismatch")
            .setMessage("Host is using $extId. Please install this extension to listen together.")
            .setPositiveButton("Download") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Wingky530/echo/releases"))
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    inner class ParticipantAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<ParticipantAdapter.VH>() {
        private var items = listOf<Participant>()

        fun updateData(newItems: List<Participant>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_listen_together_participant, parent, false))
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.badgeHost.isVisible = item.isHost
            
            val avatarUrl = item.avatarUrl ?: "https://api.dicebear.com/7.x/identicon/png?seed=${item.name}"
            holder.ivAvatar.visibility = View.VISIBLE
            holder.tvInitial.visibility = View.GONE
            holder.ivAvatar.load(avatarUrl) {
                crossfade(true)
                transformations(coil.transform.CircleCropTransformation())
            }
        }

        inner class VH(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvName)
            val ivAvatar: com.google.android.material.imageview.ShapeableImageView = view.findViewById(R.id.ivAvatar)
            val tvInitial: TextView = view.findViewById(R.id.tvAvatarInitial)
            val badgeHost: View = view.findViewById(R.id.badgeHost)
        }
    }

    companion object {
        const val TAG = "ListenTogetherBottomSheet"
        fun show(fm: androidx.fragment.app.FragmentManager, trackId: String?, extensionId: String?) {
            ListenTogetherBottomSheet().apply {
                arguments = Bundle().apply {
                    putString("trackId", trackId)
                    putString("extensionId", extensionId)
                }
            }.show(fm, TAG)
        }
    }
}
