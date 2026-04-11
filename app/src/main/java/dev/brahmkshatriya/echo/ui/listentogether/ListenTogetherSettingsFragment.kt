/**
 * Package dev.brahmkshatriya.echo.ui.listentogether
 * 
 * Purpose: A basic settings screen allowing users to configure their display name 
 * before joining or creating a listen-together session
 *
 * Key Components:
 *  - SharedPreferences instance: Used to persist the username between app sessions
 *  - TextWatcher binding: Dynamically updates the temporary avatar preview as typing occurs
 *
 * Dependencies:
 *  - android.content.SharedPreferences: Standard Android key-value storage
 */
package dev.brahmkshatriya.echo.ui.listentogether

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
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

        // Load saved username
        val savedUsername = prefs.getString(KEY_USERNAME, "") ?: ""
        binding.etUsername.setText(savedUsername)

        // Update avatar preview saat username berubah
        binding.etUsername.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val initial = s?.firstOrNull()?.uppercase() ?: "?"
                binding.tvAvatarInitial.text = initial
            }
        })

        // Set initial avatar
        val initial = savedUsername.firstOrNull()?.uppercase() ?: "?"
        binding.tvAvatarInitial.text = initial

        binding.btnSave.setOnClickListener {
            val username = binding.etUsername.text?.toString()?.trim()
            if (username.isNullOrBlank()) {
                binding.etUsername.error = getString(R.string.listen_together_username_hint)
                return@setOnClickListener
            }
            prefs.edit().putString(KEY_USERNAME, username).apply()
            Toast.makeText(context, R.string.listen_together_save, Toast.LENGTH_SHORT).show()
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
