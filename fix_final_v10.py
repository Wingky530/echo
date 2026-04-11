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
        try {
            val assetPath = "extensions/ytm_music.eapk"
            val targetName = "youtube.eapk"
            
            // Coba di 2 lokasi: Internal Private dan External Data
            val paths = listOf(
                java.io.File(filesDir, "extensions"),
                java.io.File(getExternalFilesDir(null), "extensions")
            )

            for (folder in paths) {
                if (!folder.exists()) folder.mkdirs()
                val dest = java.io.File(folder, targetName)
                
                assets.open(assetPath).use { input ->
                    java.io.FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            android.widget.Toast.makeText(this, "V10: Auto-Inject Berhasil!", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "EROR V10: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
""")
        break
with open(path, 'w') as f:
    f.writelines(lines)
print("Suntikan V10 Berhasil!")
