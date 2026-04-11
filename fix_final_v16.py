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
        val prefs = getSharedPreferences("echo_fix_v16", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("done", false)) return

        Thread {
            try {
                val folder = java.io.File(filesDir, "extensions")
                
                // INI BEDANYA: Gilas semua file/folder lama yang bikin eror path
                if (folder.exists()) {
                    folder.deleteRecursively()
                }
                folder.mkdirs()
                
                val dest = java.io.File(folder, "youtube.eapk")
                assets.open("extensions/ytm_music.eapk").use { input ->
                    java.io.FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                
                prefs.edit().putBoolean("done", true).apply()
                
                runOnUiThread {
                    android.widget.Toast.makeText(this, "V16: GILAS SAMPAH & MENDARAT!", android.widget.Toast.LENGTH_SHORT).show()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        recreate()
                    }, 800)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    android.widget.Toast.makeText(this, "V16 GAGAL: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
""")
        break

with open(path, 'w') as f:
    f.writelines(lines)
print("Suntikan V16 (Nuclear) Berhasil!")
