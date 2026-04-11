import os
path = 'app/src/main/java/dev/brahmkshatriya/echo/MainActivity.kt'
with open(path, 'r') as f:
    lines = f.readlines()

# 1. Suntik Imports yang ketinggalan biar Gak Eror Unresolved
new_imports = [
    "import androidx.lifecycle.lifecycleScope\\n",
    "import kotlinx.coroutines.launch\\n",
    "import kotlinx.coroutines.delay\\n"
]
# Cari baris package dan taruh import di bawahnya
for i in range(len(lines)):
    if lines[i].startswith("package "):
        for imp in reversed(new_imports):
            if imp not in lines:
                lines.insert(i + 1, imp)
        break

# 2. Bersihkan sisa-sisa lama
lines = [l for l in lines if 'setupDefaultExtensions()' not in l]

# 3. Suntik pemanggilan di onCreate
for i in range(len(lines)):
    if 'super.onCreate' in lines[i]:
        lines.insert(i + 1, '        setupDefaultExtensions()\\n')
        break

# 4. Suntik fungsi V12 (Pake lifecycleScope.launch)
for i in range(len(lines) - 1, -1, -1):
    if lines[i].strip() == "}":
        lines.insert(i, """
    private fun setupDefaultExtensions() {
        lifecycleScope.launch {
            try {
                val folder = java.io.File(filesDir, "extensions")
                if (folder.isFile) folder.delete()
                if (!folder.exists()) folder.mkdirs()
                
                val dest = java.io.File(folder, "youtube.eapk")
                assets.open("extensions/ytm_music.eapk").use { input ->
                    java.io.FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                delay(1500) // Kasih waktu sistem buat nafas
                android.widget.Toast.makeText(this@MainActivity, "V12: YTM READY!", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
""")
        break

with open(path, 'w') as f:
    f.writelines(lines)
print("Suntikan V12 (Build Fixer) Berhasil!")
