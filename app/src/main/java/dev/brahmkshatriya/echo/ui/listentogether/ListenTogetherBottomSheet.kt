package dev.brahmkshatriya.echo.ui.listentogether

import android.content.Intent
import android.net.Uri
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
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.databinding.BottomSheetListenTogetherBinding
import dev.brahmkshatriya.echo.ui.extensions.login.LoginUserListViewModel
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import kotlinx.coroutines.launch

class ListenTogetherBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetListenTogetherBinding? = null
    private val binding get() = _binding!!

    private val vm: ListenTogetherViewModel by activityViewModels()
    private val playerVm: PlayerViewModel by activityViewModels()
    private val loginVm: LoginUserListViewModel by activityViewModels()

    private val participantAdapter = ParticipantAdapter()
    private var previousParticipants: List<Participant> = emptyList()

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
        viewLifecycleOwner.lifecycleScope.launch {
            vm.event.collect { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() }
        }

        binding.btnCreate.setOnClickListener {
            val trackId = arguments?.getString("trackId")
            // Clear Host playlist if playing a single track
            if (!trackId.isNullOrBlank()) {
                playerVm.browser.value?.clearMediaItems()
            }
            vm.createSession(trackId, arguments?.getString("extensionId"), getActiveUsername(), getActiveAvatar())
        }

        binding.btnJoin.setOnClickListener {
            val code = binding.etCode.text?.toString()?.trim()
            if (!code.isNullOrBlank() && code.length >= 6) {
                // Clear Guest playlist before joining
                playerVm.browser.value?.clearMediaItems()
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
        
        binding.btnSettings.setOnClickListener {
            val currentPerm = vm.permission.value
            val options = arrayOf("Add to Playlist", "Playback Control")
            val checkedItems = booleanArrayOf(currentPerm >= 1, currentPerm >= 2)

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Guest Permissions")
                .setMultiChoiceItems(options, checkedItems) { _, which, isChecked ->
                    if (which == 1 && isChecked) {
                        checkedItems[0] = true // If Playback is on, Playlist must be on
                        checkedItems[1] = true
                    } else if (which == 0 && !isChecked) {
                        checkedItems[0] = false
                        checkedItems[1] = false // If Playlist is off, Playback must be off
                    } else {
                        checkedItems[which] = isChecked
                    }
                }
                .setPositiveButton("Save") { _, _ ->
                    val newLevel = when {
                        checkedItems[1] -> 2 
                        checkedItems[0] -> 1 
                        else -> 0        
                    }
                    vm.updatePermission(newLevel)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
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
            val leftUsers = previousParticipants.filter { p -> state.participants.none { it.id == p.id } }
            leftUsers.forEach { user -> Toast.makeText(requireContext(), "${user.name} has left the session", Toast.LENGTH_SHORT).show() }
            val joinedUsers = state.participants.filter { p -> previousParticipants.none { it.id == p.id } }
            if (previousParticipants.isNotEmpty()) { joinedUsers.forEach { user -> Toast.makeText(requireContext(), "${user.name} joined the session", Toast.LENGTH_SHORT).show() } }
            previousParticipants = state.participants
            binding.tvSessionCode.text = state.sessionCode
            binding.tvRole.text = if (state.isHost) getString(R.string.listen_together_you_host) else getString(R.string.listen_together_listening_with)
            binding.btnSettings.isVisible = true
            binding.btnSettings.alpha = if (state.isHost) 1.0f else 0.5f
            binding.btnSettings.isEnabled = state.isHost

            participantAdapter.updateData(state.participants.sortedWith(compareBy({ !it.isHost }, { it.name })))
            binding.tvParticipants.text = "Participants (${state.participants.size})"
        }

        if (state !is ListenTogetherState.Active) previousParticipants = emptyList()
        if (state is ListenTogetherState.Error) Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    inner class ParticipantAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<ParticipantAdapter.VH>() {
        private var items = listOf<Participant>()
        fun updateData(newItems: List<Participant>) { items = newItems; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_listen_together_participant, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.badgeHost.isVisible = item.isHost
            val avatarUrl = item.avatarUrl ?: "https://api.dicebear.com/7.x/identicon/png?seed=${item.name}"
            holder.ivAvatar.load(avatarUrl) { crossfade(true); transformations(coil.transform.CircleCropTransformation()) }
        }
        inner class VH(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvName)
            val ivAvatar: com.google.android.material.imageview.ShapeableImageView = view.findViewById(R.id.ivAvatar)
            val badgeHost: View = view.findViewById(R.id.badgeHost)
        }
    }

    companion object {
        const val TAG = "ListenTogetherBottomSheet"
        fun show(fm: androidx.fragment.app.FragmentManager, trackId: String?, extensionId: String?) {
            ListenTogetherBottomSheet().apply { arguments = Bundle().apply { putString("trackId", trackId); putString("extensionId", extensionId) } }.show(fm, TAG)
        }
    }
}
