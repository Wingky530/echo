import os
file_path = 'app/src/main/java/dev/brahmkshatriya/echo/MainActivity.kt'
with open(file_path, 'r') as f:
    content = f.read()

new_func = """
fun android.content.Context.installDefaultExtensions() {
    val extDir = java.io.File(this.filesDir, "extensions")
    if (!extDir.exists()) extDir.mkdirs()
    val ytmFile = java.io.File(extDir, "youtube.apk")
    if (!ytmFile.exists()) {
        try {
            this.assets.open("extensions/youtube.eapk").use { input ->
                ytmFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            android.widget.Toast.makeText(this, "YTM Pre-installed!", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}"""

# Bersihkan fungsi lama kalau ada, lalu tempel yang baru
if "fun android.content.Context.installDefaultExtensions()" in content:
    content = content.split("fun android.content.Context.installDefaultExtensions()")[0]
content += new_func

with open(file_path, 'w') as f:
    f.write(content)
