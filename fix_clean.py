import os

path = 'app/src/main/java/dev/brahmkshatriya/echo/MainActivity.kt'
with open(path, 'r') as f:
    lines = f.readlines()

# 1. Suntik pemanggilan fungsi di onCreate
for i in range(len(lines)):
    if 'super.onCreate(savedInstanceState)' in lines[i]:
        # Masukin tepat di bawah super.onCreate
        lines.insert(i + 1, '        setupDefaultExtensions()\n')
        break

# 2. Suntik definisi fungsi di akhir class (sebelum tutup kurung terakhir)
for i in range(len(lines) - 1, -1, -1):
    if lines[i].strip() == '}':
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
""")
        break

with open(path, 'w') as f:
    f.writelines(lines)
