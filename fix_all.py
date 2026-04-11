import os
path = 'app/src/main/java/dev/brahmkshatriya/echo/MainActivity.kt'
with open(path, 'r') as f:
    lines = f.readlines()

# 1. Bersihkan sisa-sisa setupDefaultExtensions yang lama
lines = [l for l in lines if 'setupDefaultExtensions()' not in l]

# 2. Cari baris super.onCreate dan selipkan pemanggilannya di bawahnya
for i in range(len(lines)):
    if 'super.onCreate' in lines[i]:
        lines.insert(i+1, '        setupDefaultExtensions()\n')
        break

# 3. Cari baris terakhir yang cuma "}" (penutup class)
for i in range(len(lines)-1, -1, -1):
    if lines[i].strip() == "}":
        # Masukin fungsi pre-install sebelum tutup class agar tidak jadi 'top level'
        lines.insert(i, """
    private fun setupDefaultExtensions() {
        val extDir = java.io.File(filesDir, "extensions")
        if (!extDir.exists()) extDir.mkdirs()
        val ytmFile = java.io.File(extDir, "youtube_music.apk")
        if (!ytmFile.exists()) {
            try {
                assets.open("extensions/youtube.eapk").use { input ->
                    ytmFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                android.widget.Toast.makeText(this, "YTM Ready!", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
""")
        break

with open(path, 'w') as f:
    f.writelines(lines)
