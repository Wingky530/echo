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
            // Paksa buat folder dan semua induknya
            if (!folder.exists()) {
                val result = folder.mkdirs()
                if (!result) {
                    android.widget.Toast.makeText(this, "SISTEM: Folder Gagal Dibuat!", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            
            val destination = java.io.File(folder, "youtube.eapk")
            val assetPath = "extensions/ytm_music.eapk"
            
            assets.open(assetPath).use { input ->
                java.io.FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
            android.widget.Toast.makeText(this, "BERHASIL: YTM Mendarat!", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // Cek ada berapa file di folder assets/extensions
            val list = assets.list("extensions")
            val count = list?.size ?: 0
            val files = list?.joinToString(", ") ?: "Kosong"
            android.widget.Toast.makeText(this, "GAGAL: ${e.message}\\nAssets: $count file ($files)", android.widget.Toast.LENGTH_LONG).show()
        }
    }
""")
        break
with open(path, 'w') as f:
    f.writelines(lines)
print("Suntikan V7 Berhasil!")
