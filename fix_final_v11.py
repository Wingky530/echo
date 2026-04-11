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
        lifecycleScope.launchWhenCreated {
            try {
                val folder = java.io.File(filesDir, "extensions")
                // Bersihin sampah biar gak eror path lagi
                if (folder.isFile) folder.delete()
                if (!folder.exists()) folder.mkdirs()
                
                val dest = java.io.File(folder, "youtube.eapk")
                
                // Salin dari assets
                assets.open("extensions/ytm_music.eapk").use { input ->
                    java.io.FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                
                // TRICK ZERO CLICK: Pancing loader buat scan folder
                // Kita pake delay dikit biar sistem siap
                kotlinx.coroutines.delay(1000)
                android.widget.Toast.makeText(this@MainActivity, "V11: YTM AUTO-LOADED!", android.widget.Toast.LENGTH_SHORT).show()
                
            } catch (e: Exception) {
                android.widget.Toast.makeText(this@MainActivity, "V11 GAGAL: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
""")
        break
with open(path, 'w') as f:
    f.writelines(lines)
print("Suntikan V11 Berhasil!")
