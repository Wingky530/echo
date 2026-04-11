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
            if (!folder.exists()) folder.mkdirs()
            val destination = java.io.File(folder, "youtube_music.apk")
            assets.open("extensions/ytm_music.eapk").use { input ->
                java.io.FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
            android.widget.Toast.makeText(this, "YTM Berhasil Terpasang!", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
""")
        break
with open(path, 'w') as f:
    f.writelines(lines)
print("Suntikan Final V5 Berhasil!")
