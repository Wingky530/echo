import os

path = 'app/src/main/java/dev/brahmkshatriya/echo/MainActivity.kt'
with open(path, 'r') as f:
    lines = f.readlines()

# 1. Bersihkan sisa-sisa lama biar gak duplikat
lines = [l for l in lines if 'setupDefaultExtensions()' not in l]

# 2. Suntik pemanggilan di onCreate
for i in range(len(lines)):
    if 'super.onCreate' in lines[i]:
        lines.insert(i + 1, '        setupDefaultExtensions()\n')
        break

# 3. Cari kurung kurawal penutup class yang paling TERAKHIR
# Kita cari dari bawah ke atas
last_brace = -1
for i in range(len(lines)-1, -1, -1):
    if lines[i].strip() == "}":
        last_brace = i
        break

if last_brace != -1:
    # Selipkan fungsinya TEPAT SEBELUM kurung tutup class
    lines.insert(last_brace, """
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

with open(path, 'w') as f:
    f.writelines(lines)
print("Suntikan Berhasil! File MainActivity sekarang steril.")
