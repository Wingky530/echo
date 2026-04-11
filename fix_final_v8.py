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
            val folder = java.io.File(filesDir, "extensions")
            
            // Hapus paksa kalau ada file/folder lama yang bikin macet
            if (folder.exists()) {
                folder.deleteRecursively()
            }
            folder.mkdirs()
            
            val destination = java.io.File(folder, "youtube.eapk")
            val assetPath = "extensions/ytm_music.eapk"
            
            assets.open(assetPath).use { input ->
                java.io.FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
            android.widget.Toast.makeText(this, "AKHIRNYA: YTM BERHASIL!", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            val list = assets.list("extensions")?.joinToString(", ") ?: "Kosong"
            android.widget.Toast.makeText(this, "GAGAL: ${e.message}\\nIsi Assets: $list", android.widget.Toast.LENGTH_LONG).show()
        }
    }
""")
        break
with open(path, 'w') as f:
    f.writelines(lines)
print("Suntikan V8 Berhasil!")
