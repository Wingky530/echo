import os

path = 'app/src/main/java/dev/brahmkshatriya/echo/MainActivity.kt'
with open(path, 'r') as f:
    lines = f.readlines()

# 1. Bersihkan fungsi lama
clean_lines = []
skip = False
for line in lines:
    if 'private fun setupDefaultExtensions()' in line:
        skip = True
    if not skip:
        clean_lines.append(line)
    if skip and line.strip() == '}':
        skip = False
        continue

# 2. Hapus pemanggilan lama
clean_lines = [l for l in clean_lines if 'setupDefaultExtensions()' not in l]

# 3. Suntik pemanggilan di onCreate
for i in range(len(clean_lines)):
    if 'super.onCreate' in clean_lines[i]:
        clean_lines.insert(i + 1, '        setupDefaultExtensions()\n')
        break

# 4. Suntik fungsi SEBELUM tutup kurung class terakhir
for i in range(len(clean_lines) - 1, -1, -1):
    if clean_lines[i].strip() == '}':
        clean_lines.insert(i, """
    private fun setupDefaultExtensions() {
        android.widget.Toast.makeText(this, "DEBUG: Memulai Pasang YTM...", android.widget.Toast.LENGTH_LONG).show()
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
    f.writelines(clean_lines)
print("Suntikan YTM Berhasil Kesimpen!")
