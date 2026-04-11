import os

path = 'app/src/main/java/dev/brahmkshatriya/echo/MainActivity.kt'
with open(path, 'r') as f:
    lines = f.readlines()

# 1. Suntik Imports di paling atas (setelah package)
new_imports = [
    "import androidx.lifecycle.lifecycleScope\n",
    "import kotlinx.coroutines.launch\n",
    "import kotlinx.coroutines.delay\n"
]
for i in range(len(lines)):
    if lines[i].startswith("package "):
        for imp in reversed(new_imports):
            if imp not in lines:
                lines.insert(i + 1, imp)
        break

# 2. Suntik pemanggilan di onCreate
for i in range(len(lines)):
    if 'super.onCreate' in lines[i]:
        if 'setupDefaultExtensions()' not in lines[i+1]:
            lines.insert(i + 1, '        setupDefaultExtensions()\n')
        break

# 3. Suntik fungsi di akhir class (sebelum kurung tutup terakhir)
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
                delay(2000)
                android.widget.Toast.makeText(this@MainActivity, "V13: AUTO-INJECT BERHASIL!", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
""")
        break

with open(path, 'w') as f:
    f.writelines(lines)
print("Suntikan V13 Berhasil!")
