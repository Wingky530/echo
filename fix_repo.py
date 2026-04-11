import os

file_path = 'app/src/main/java/dev/brahmkshatriya/echo/extensions/repo/CombinedRepository.kt'
with open(file_path, 'r') as f:
    lines = f.readlines()

new_lines = []
inserted_repo = False

for line in lines:
    new_lines.append(line)
    # Kita cari tempat inisialisasi AppRepository
    if "private val appRepository =" in line and not inserted_repo:
        # Kita suntikkan repositori tambahan di bawahnya
        new_lines.append('    private val extraRepoUrl = "https://api.github.com/repos/Abhishek890/Eco-Youtube_Music/releases"\n')
        inserted_repo = True

# Modifikasi flow buat gabungin repositori tambahan (sederhananya kita suruh appRepository buat nambahin url ini)
# Tapi karena kita mau yang instan, kita ganti fungsi loadExtensions-nya saja

content = "".join(new_lines)
if "override suspend fun loadExtensions()" in content:
    # Logika: Sebelum load, kita pastiin repo YTM terdaftar
    old_func = "override suspend fun loadExtensions() = flow.first { it != null } ?: list"
    new_func = """override suspend fun loadExtensions(): List<Result<Pair<Metadata, Lazy<ExtensionClient>>>> {
        try {
            // Memaksa aplikasi buat nambahin repositori YTM di latar belakang
            appRepository.addRepository(extraRepoUrl)
        } catch (e: Exception) {}
        return flow.first { it != null } ?: list
    }"""
    content = content.replace(old_func, new_func)

with open(file_path, 'w') as f:
    f.write(content)
