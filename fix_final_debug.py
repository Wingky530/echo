import os
path = 'app/src/main/java/dev/brahmkshatriya/echo/MainActivity.kt'
with open(path, 'r') as f:
    lines = f.readlines()

# 1. Bersihkan sisa-sisa lama
lines = [l for l in lines if 'setupDefaultExtensions()' not in l]

# 2. Suntik pemanggilan di onCreate
for i in range(len(lines)):
    if 'super.onCreate' in lines[i]:
        lines.insert(i + 1, '        setupDefaultExtensions()\n')
        break

# 3. Suntik fungsi dengan logika penghapusan folder sampah
for i in range(len(lines) - 1, -1, -1):
    if lines[i].strip() == "}":
        lines.insert(i, """
    private fun setupDefaultExtensions() {
        try {
            val folder = java.io.File(filesDir, "extensions")
            
            // Jika ada file dengan nama 'extensions', hapus!
            if (folder.exists() && !folder.isDirectory) {
                folder.delete()
            }
            
            // Buat folder baru jika belum ada
            if (!folder.exists()) {
                folder.mkdirs()
            }
            
            val destination = java.io.File(folder, "youtube.apk")
            
            // Proses Copy dari Assets
            assets.open("extensions/youtube.eapk").use { input ->
                java.io.FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
            
            android.widget.Toast.makeText(this, "SUKSES: ${destination.name} (${destination.length()} bytes)", android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // Tampilkan nama class error agar lebih jelas
            val fullError = "${e.javaClass.simpleName}: ${e.message}"
            android.widget.Toast.makeText(this, "LOG: $fullError", android.widget.Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }
""")
        break

with open(path, 'w') as f:
    f.writelines(lines)
print("Script Super Debug Berhasil!")
