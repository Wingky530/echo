import os
path = 'app/src/main/java/dev/brahmkshatriya/echo/MainActivity.kt'
with open(path, 'r') as f:
    lines = f.readlines()

# Cari baris terakhir yang isinya cuma "}" (penutup class)
for i in range(len(lines)-1, -1, -1):
    if lines[i].strip() == "}":
        # Masukin fungsi kurir sebelum tutup class
        lines.insert(i, """
    private fun installDefaultExtensions() {
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
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
""")
        break

content = "".join(lines)
# Panggil fungsinya di onCreate
if "super.onCreate(savedInstanceState)" in content:
    content = content.replace("super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\\n        installDefaultExtensions()")

with open(path, 'w') as f:
    f.write(content)
