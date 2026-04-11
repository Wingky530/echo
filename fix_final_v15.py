import os
path = 'app/src/main/java/dev/brahmkshatriya/echo/MainActivity.kt'
with open(path, 'r') as f:
    lines = f.readlines()

lines = [l for l in lines if 'setupDefaultExtensions()' not in l]

for i in range(len(lines)):
    if 'super.onCreate' in lines[i]:
        lines.insert(i + 1, '        setupDefaultExtensions()\n')
        break

for i in range(len(lines) - 1, -1, -1):
    if lines[i].strip() == "}":
        lines.insert(i, """
    private fun setupDefaultExtensions() {
        val prefs = getSharedPreferences("echo_fix", android.content.Context.MODE_PRIVATE)
        val isDone = prefs.getBoolean("ytm_installed", false)
        
        if (isDone) return // Jangan lari kalau sudah sukses biar gak looping

        Thread {
            try {
                val folder = java.io.File(filesDir, "extensions")
                if (!folder.exists()) folder.mkdirs()
                
                // Copy dengan nama standar yang biasanya dicari aplikasi
                val dest = java.io.File(folder, "youtube.eapk")
                assets.open("extensions/ytm_music.eapk").use { input ->
                    java.io.FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                
                prefs.edit().putBoolean("ytm_installed", true).apply()
                
                runOnUiThread {
                    android.widget.Toast.makeText(this, "V15: NYALIN SUKSES, REFRESHING...", android.widget.Toast.LENGTH_SHORT).show()
                    // RECREATE: Jurus sakti biar aplikasi scan ulang folder extensions
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        recreate()
                    }, 1000)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
""")
        break

with open(path, 'w') as f:
    f.writelines(lines)
print("Suntikan V15 Berhasil!")
