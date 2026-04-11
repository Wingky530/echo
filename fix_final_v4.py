import os

path = 'app/src/main/java/dev/brahmkshatriya/echo/MainActivity.kt'
with open(path, 'r') as f:
    lines = f.readlines()

# Bersihkan sisa-sisa lama jika ada
lines = [l for l in lines if 'setupDefaultExtensions()' not in l]

# Suntik pemanggilan di onCreate
for i in range(len(lines)):
    if 'super.onCreate' in lines[i]:
        lines.insert(i + 1, '        setupDefaultExtensions()\n')
        break

# Suntik fungsi di akhir class
for i in range(len(lines) - 1, -1, -1):
    if lines[i].strip() == "}":
        lines.insert(i, """
    private fun setupDefaultExtensions() {
        try {
            val folder = java.io.File(filesDir, "extensions")
            if (!folder.exists()) folder.mkdirs()
            val destination = java.io.File(folder, "youtube.apk")
            val assetList = assets.list("extensions")?.joinToString(", ") ?: "Kosong"
            
            try {
                assets.open("extensions/youtube.eapk").use { input ->
                    java.io.FileOutputStream(destination).use { output ->
                        input.copyTo(output)
                    }
                }
                android.widget.Toast.makeText(this, "SUKSES: YTM Terpasang!", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "ASSETS ERROR: 'youtube.eapk' gak ada di APK. Isi assets/extensions: $assetList", android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "SYSTEM ERROR: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
""")
        break

with open(path, 'w') as f:
    f.writelines(lines)
print("Suntikan Berhasil! Siap Push.")
