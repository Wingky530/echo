import os
path = 'app/src/main/java/dev/brahmkshatriya/echo/MainActivity.kt'
with open(path, 'r') as f:
    lines = f.readlines()

# Bersihkan sisa-sisa lama
lines = [l for l in lines if 'setupDefaultExtensions()' not in l]

# Suntik pemanggilan di onCreate
for i in range(len(lines)):
    if 'super.onCreate' in lines[i]:
        lines.insert(i + 1, '        setupDefaultExtensions()\n')
        break

# Suntik fungsi dengan TOAST ERROR
for i in range(len(lines) - 1, -1, -1):
    if lines[i].strip() == "}":
        lines.insert(i, """
    private fun setupDefaultExtensions() {
        try {
            val folder = java.io.File(filesDir, "extensions")
            if (!folder.exists()) folder.mkdirs()
            
            // Nama file tujuan harus youtube.eapk biar kebaca sistem
            val destination = java.io.File(folder, "youtube.eapk")
            
            assets.open("extensions/ytm_music.eapk").use { input ->
                java.io.FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
            android.widget.Toast.makeText(this, "BERHASIL: File YTM disalin!", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "GAGAL: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
""")
        break
with open(path, 'w') as f:
    f.writelines(lines)
print("Suntikan Debug V6 Berhasil!")
