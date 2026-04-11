import os
path = 'app/src/main/java/dev/brahmkshatriya/echo/MainActivity.kt'
with open(path, 'r') as f:
    lines = f.readlines()

# Bersihkan fungsi lama agar tidak tumpang tindih
new_lines = []
skip = False
for line in lines:
    if 'private fun setupDefaultExtensions()' in line:
        skip = True
    if not skip:
        new_lines.append(line)
    if skip and line.strip() == '}':
        skip = False
        continue

# Cari baris penutup class terakhir
for i in range(len(new_lines) - 1, -1, -1):
    if new_lines[i].strip() == '}':
        new_lines.insert(i, """
    private fun setupDefaultExtensions() {
        android.widget.Toast.makeText(this, "DEBUG: Memulai Instalasi YTM...", android.widget.Toast.LENGTH_LONG).show()
        val extDir = java.io.File(filesDir, "extensions")
        if (!extDir.exists()) extDir.mkdirs()
        val ytmFile = java.io.File(extDir, "youtube.apk")
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
""")
        break

with open(path, 'w') as f:
    f.writelines(new_lines)
