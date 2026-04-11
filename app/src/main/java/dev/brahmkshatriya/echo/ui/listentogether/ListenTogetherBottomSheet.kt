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
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.databinding.BottomSheetListenTogetherBinding
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.ui.extensions.login.LoginUserListViewModel
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class ListenTogetherBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "ListenTogetherBottomSheet"
        fun show(fm: FragmentManager, extensionId: String, trackId: String?) = ListenTogetherBottomSheet().apply {
            arguments = Bundle().apply { putString("extensionId", extensionId); putString("trackId", trackId) }
        }.show(fm, TAG)
    }

    private var _binding: BottomSheetListenTogetherBinding? = null
    private val binding get() = _binding!!

    private val vm: ListenTogetherViewModel by activityViewModel()
    private val playerVm: PlayerViewModel by activityViewModel()
    private val loginVm: LoginUserListViewModel by activityViewModel()
    
    private val participantAdapter = ParticipantAdapter()
    private var previousParticipants: List<Participant>? = null
    private var permissions = booleanArrayOf(true, true, true)

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
        observe(vm.state) { renderState(it) }

        binding.btnCreate.setOnClickListener {
            vm.createSession(arguments?.getString("trackId"), arguments?.getString("extensionId"), getActiveUsername(), loginVm.currentUser.value?.cover?.toString())
        }

        binding.btnJoin.setOnClickListener {
            val code = binding.etCode.text?.toString()?.trim()
            if (!code.isNullOrBlank() && code.length >= 6) vm.joinSession(code, getActiveUsername(), loginVm.currentUser.value?.cover?.toString())
            else binding.etCode.error = getString(R.string.listen_together_code_hint)
        }

        binding.btnCopy.setOnClickListener {
            val s = vm.state.value as? ListenTogetherState.Active ?: return@setOnClickListener
            (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", s.sessionCode))
            Toast.makeText(context, R.string.listen_together_copied, Toast.LENGTH_SHORT).show()
        }

        binding.btnSettings.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Listener Permissions")
                .setMultiChoiceItems(arrayOf("Allow Play / Pause", "Allow Seek Duration", "Allow Change Track"), permissions) { _, which, isChecked ->
                    permissions[which] = isChecked
                }
                .setPositiveButton("Save", null)
                .show()
        }

        binding.btnLeave.setOnClickListener { vm.leaveSession() }
    }
    
    private fun getActiveUsername() = loginVm.currentUser.value?.name ?: ListenTogetherSettingsFragment.getUsername(requireContext()).takeIf { it.isNotBlank() } ?: "Guest"

    private fun renderState(state: ListenTogetherState) {
        binding.panelSetup.isVisible = state is ListenTogetherState.Idle || state is ListenTogetherState.Error
        binding.progressConnecting.isVisible = state is ListenTogetherState.Connecting
        binding.panelActive.isVisible = state is ListenTogetherState.Active

        if (state is ListenTogetherState.Active) {
            binding.tvSessionCode.text = state.sessionCode
            binding.tvRole.text = if (state.isHost) getString(R.string.listen_together_you_host) else getString(R.string.listen_together_listening_with)
            binding.btnSettings.isVisible = state.isHost
            
            val oldList = previousParticipants
            if (oldList != null) {
                state.participants.filter { newP -> oldList.none { it.id == newP.id } }.forEach { p -> Toast.makeText(context, "${p.name} joined", Toast.LENGTH_SHORT).show() }
                oldList.filter { oldP -> state.participants.none { it.id == oldP.id } }.forEach { p -> Toast.makeText(context, "${p.name} left", Toast.LENGTH_SHORT).show() }
            }
            previousParticipants = state.participants
            
            participantAdapter.updateData(state.participants.sortedWith(compareBy({ !it.isHost }, { it.name })))
            binding.tvParticipants.text = "Participants (${state.participants.size})"
        } else previousParticipants = null

        if (state is ListenTogetherState.Error) Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    inner class ParticipantAdapter : RecyclerView.Adapter<ParticipantAdapter.VH>() {
        private var items = listOf<Participant>()
        fun updateData(newItems: List<Participant>) { items = newItems; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_listen_together_participant, parent, false))
        override fun getItemCount() = items.size
        
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.badgeHost.isVisible = item.isHost
            holder.tvInitial.text = item.name.firstOrNull()?.uppercase() ?: "?"
            holder.ivAvatar.setImageResource(0)
        }
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvName)
            val ivAvatar: com.google.android.material.imageview.ShapeableImageView = view.findViewById(R.id.ivAvatar)
            val tvInitial: TextView = view.findViewById(R.id.tvAvatarInitial)
            val badgeHost: View = view.findViewById(R.id.badgeHost)
        }
    }
}
