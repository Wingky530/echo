import os
path = 'app/src/main/java/dev/brahmkshatriya/echo/MainActivity.kt'
with open(path, 'r') as f:
    lines = f.readlines()

# 1. Bersihkan sisa-sisa pemanggilan yang mungkin tertinggal
lines = [l for l in lines if 'setupDefaultExtensions()' not in l]

# 2. Cari baris super.onCreate dan selipkan pemanggilannya
for i in range(len(lines)):
    if 'super.onCreate' in lines[i]:
        lines.insert(i + 1, '        setupDefaultExtensions()\n')
        break

# 3. Cari kurung kurawal penutup class yang paling terakhir
last_brace_index = -1
for i in range(len(lines) - 1, -1, -1):
    if lines[i].strip() == '}':
        last_brace_index = i
        break

if last_brace_index != -1:
    # Masukkan fungsi SEBELUM kurung kurawal terakhir agar tetap di dalam class
    lines.insert(last_brace_index, """
    private fun setupDefaultExtensions() {
        val extDir = java.io.File(filesDir, "extensions")
        if (!extDir.exists()) extDir.mkdirs()
        val ytmFile = java.io.File(extDir, "youtube.apk")
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

with open(path, 'w') as f:
    f.writelines(lines)
