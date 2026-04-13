package dev.brahmkshatriya.echo.ui.listentogether

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import coil.load
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.databinding.FragmentListenTogetherSettingsBinding

class ListenTogetherSettingsFragment : Fragment() {

    companion object {
        const val PREF_NAME = "listen_together_prefs"
        const val KEY_USERNAME = "username"
        const val KEY_AVATAR_COLOR = "avatar_color"

        fun getUsername(context: Context): String {
            return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_USERNAME, "") ?: ""
        }

        fun getAvatarColor(context: Context): Int {
            return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_AVATAR_COLOR, 0xFF6200EE.toInt())
        }
    }

    private var _binding: FragmentListenTogetherSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentListenTogetherSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedUsername = prefs.getString(KEY_USERNAME, "") ?: ""
        
        binding.etUsername.setText(savedUsername)

        fun updatePreview(name: String) {
            if (name.isNotBlank()) {
                binding.tvAvatarInitial.visibility = View.GONE
                binding.ivIdenticonPreview.visibility = View.VISIBLE
                binding.ivIdenticonPreview.load("https://api.dicebear.com/7.x/identicon/png?seed=$name") {
                    crossfade(true)
                    transformations(coil.transform.CircleCropTransformation())
                }
            } else {
                binding.tvAvatarInitial.visibility = View.VISIBLE
                binding.ivIdenticonPreview.visibility = View.GONE
                binding.tvAvatarInitial.text = "?"
            }
        }

        updatePreview(savedUsername)

        binding.etUsername.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updatePreview(s?.toString()?.trim() ?: "")
            }
        })

        binding.btnSave.setOnClickListener {
            val username = binding.etUsername.text?.toString()?.trim()
            if (username.isNullOrBlank()) {
                binding.etUsername.error = getString(R.string.listen_together_username_hint)
                return@setOnClickListener
            }
            prefs.edit().putString(KEY_USERNAME, username).apply()
            Toast.makeText(requireContext(), R.string.listen_together_save, Toast.LENGTH_SHORT).show()
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        binding.toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
