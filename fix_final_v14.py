import os
path = 'app/src/main/java/dev/brahmkshatriya/echo/MainActivity.kt'
with open(path, 'r') as f:
    lines = f.readlines()

lines = [l for l in lines if 'setupDefaultExtensions()' not in l]

for i in range(len(lines)):
    if 'super.onCreate' in lines[i]:
        # Tambahin Toast langsung buat ngetes apakah kodingan ini beneran ada di APK lu
        lines.insert(i + 1, '        android.widget.Toast.makeText(this, "BOOTING V14...", android.widget.Toast.LENGTH_SHORT).show()\n')
        lines.insert(i + 2, '        setupDefaultExtensions()\n')
        break

for i in range(len(lines) - 1, -1, -1):
    if lines[i].strip() == "}":
        lines.insert(i, """
    private fun setupDefaultExtensions() {
        // Pake Thread biasa biar gak ribet urusan coroutine scope
        Thread {
            try {
                val folder = java.io.File(filesDir, "extensions")
                if (folder.isFile) folder.delete()
                if (!folder.exists()) folder.mkdirs()
                
                val dest = java.io.File(folder, "youtube.eapk")
                assets.open("extensions/ytm_music.eapk").use { input ->
                    java.io.FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                runOnUiThread {
                    android.widget.Toast.makeText(this, "V14: YTM MENDARAT!", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    android.widget.Toast.makeText(this, "V14 GAGAL: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
""")
        break

with open(path, 'w') as f:
    f.writelines(lines)
print("Suntikan V14 Berhasil!")
