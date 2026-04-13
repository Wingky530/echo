package dev.brahmkshatriya.echo.ui.listentogether

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
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

    override fun onCreateView(inflater: LayoutInflater, p: ViewGroup?, s: Bundle?): View {
        _binding = BottomSheetListenTogetherBinding.inflate(inflater, p, false)
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
        viewLifecycleOwner.lifecycleScope.launch { vm.state.collect { renderState(it) } }
        viewLifecycleOwner.lifecycleScope.launch { 
            vm.event.collect { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() } 
        }

        binding.btnCreate.setOnClickListener {
            val trackId = arguments?.getString("trackId")
            if (!trackId.isNullOrBlank()) playerVm.browser.value?.clearMediaItems()
            vm.createSession(trackId, arguments?.getString("extensionId"), getActiveUsername(), getActiveAvatar())
        }

        binding.btnJoin.setOnClickListener {
            val code = binding.etCode.text?.toString()?.trim()
            if (!code.isNullOrBlank() && code.length >= 6) {
                playerVm.browser.value?.clearMediaItems()
                vm.joinSession(code, getActiveUsername(), getActiveAvatar())
            } else { Toast.makeText(requireContext(), "Invalid Code", Toast.LENGTH_SHORT).show() }
        }

        binding.btnCopy.setOnClickListener {
            val s = vm.state.value as? ListenTogetherState.Active ?: return@setOnClickListener
            val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("code", s.sessionCode))
            Toast.makeText(requireContext(), "Code Copied", Toast.LENGTH_SHORT).show()
        }

        binding.btnLeave.setOnClickListener { vm.leaveSession() }
        
        binding.btnSettings.setOnClickListener {
            val context = requireContext()
            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(60, 40, 60, 0)
            }
            val curPerm = vm.permission.value
            val swFull = SwitchMaterial(context).apply { text = "Full Control"; isChecked = curPerm == 3 }
            val swAdd = SwitchMaterial(context).apply { text = "Add/Remove Music"; isChecked = (curPerm and 1) != 0 }
            val swPlay = SwitchMaterial(context).apply { text = "Playback Control"; isChecked = (curPerm and 2) != 0 }

            swFull.setOnCheckedChangeListener { _, isChecked ->
                swAdd.isChecked = isChecked
                swPlay.isChecked = isChecked
            }
            layout.addView(swFull); layout.addView(swAdd); layout.addView(swPlay)

            MaterialAlertDialogBuilder(context)
                .setTitle("Guest Permissions")
                .setView(layout)
                .setPositiveButton("Save") { _, _ ->
                    var lv = 0
                    if (swAdd.isChecked) lv += 1
                    if (swPlay.isChecked) lv += 2
                    vm.updatePermission(lv)
                }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun renderState(state: ListenTogetherState) {
        binding.panelSetup.isVisible = state is ListenTogetherState.Idle || state is ListenTogetherState.Error
        binding.progressConnecting.isVisible = state is ListenTogetherState.Connecting
        binding.panelActive.isVisible = state is ListenTogetherState.Active

        if (state is ListenTogetherState.Active) {
            val currentList = state.participants
            
            // Restore missing leftUsers detection
            val leftUsers = previousParticipants.filter { p -> currentList.none { it.id == p.id } }
            leftUsers.forEach { Toast.makeText(requireContext(), "${it.name} left", Toast.LENGTH_SHORT).show() }

            val joinedUsers = currentList.filter { p -> previousParticipants.none { it.id == p.id } }
            if (previousParticipants.isNotEmpty()) {
                joinedUsers.forEach { Toast.makeText(requireContext(), "${it.name} joined", Toast.LENGTH_SHORT).show() }
            }
            previousParticipants = currentList
            binding.tvSessionCode.text = state.sessionCode
            binding.btnSettings.isVisible = state.isHost
            participantAdapter.updateData(currentList.sortedByDescending { it.isHost })
        } else {
            previousParticipants = emptyList()
        }

        if (state is ListenTogetherState.Error) {
            Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun getActiveUsername(): String {
        val name = loginVm.currentUser.value?.name
        return if (!name.isNullOrBlank()) name else "Guest"
    }

    private fun getActiveAvatar(): String? {
        val cover = loginVm.currentUser.value?.cover ?: return null
        return if (cover is ImageHolder.NetworkRequestImageHolder) cover.request.url else null
    }

    inner class ParticipantAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<ParticipantAdapter.VH>() {
        private var items = listOf<Participant>()
        fun updateData(newItems: List<Participant>) { items = newItems; notifyDataSetChanged() }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_listen_together_participant, p, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = items[pos]
            h.tvName.text = item.name
            h.badgeHost.isVisible = item.isHost
            h.ivAvatar.load(item.avatarUrl ?: "https://api.dicebear.com/7.x/identicon/png?seed=${item.name}") {
                transformations(coil.transform.CircleCropTransformation())
            }
        }
        inner class VH(v: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName)
            val ivAvatar: com.google.android.material.imageview.ShapeableImageView = v.findViewById(R.id.ivAvatar)
            val badgeHost: View = v.findViewById(R.id.badgeHost)
        }
    }

    companion object {
        const val TAG = "ListenTogetherBottomSheet"
        fun show(fm: androidx.fragment.app.FragmentManager, trackId: String? = null, extensionId: String? = null) {
            ListenTogetherBottomSheet().apply {
                arguments = Bundle().apply {
                    putString("trackId", trackId)
                    putString("extensionId", extensionId)
                }
            }.show(fm, TAG)
        }
    }
}
