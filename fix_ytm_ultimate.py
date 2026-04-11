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

# 3. Cari tutup kurung class terakhir
last_brace = -1
for i in range(len(lines)-1, -1, -1):
    if lines[i].strip() == "}":
        last_brace = i
        break

if last_brace != -1:
    lines.insert(last_brace, """
    private fun setupDefaultExtensions() {
        val extDir = java.io.File(filesDir, "extensions")
        if (!extDir.exists()) {
            val created = extDir.mkdirs()
            if (!created) {
                android.widget.Toast.makeText(this, "GAGAL: Folder extensions gak bisa dibuat", android.widget.Toast.LENGTH_LONG).show()
                return
            }
        }
        
        val ytmFile = java.io.File(extDir, "youtube.apk")
        if (ytmFile.exists()) ytmFile.delete() // Hapus yang lama biar gak korup

        try {
            assets.open("extensions/youtube.eapk").use { input ->
                ytmFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (ytmFile.exists() && ytmFile.length() > 0) {
                android.widget.Toast.makeText(this, "YTM BERHASIL! Size: ${ytmFile.length()} bytes", android.widget.Toast.LENGTH_LONG).show()
            } else {
                android.widget.Toast.makeText(this, "ERROR: File nol byte!", android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "DETEKSI ERROR: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }
""")

with open(path, 'w') as f:
    f.writelines(lines)
print("Suntikan Ultimate Berhasil!")
