# chromadb збирає свої функції вбудовування через
# pkgutil.iter_modules([<тека пакета>]) — у пакеті PyInstaller модулі лежать в
# архіві, теки нема, і `from chromadb.utils.embedding_functions import …`
# падає «name 'ONNXMiniLM_L6_V2' is not defined» (прогін вузла з пакета, 26.09).
# Тож кладемо .py цього підпакета на диск (імпорт однаково йде з архіву), а
# разом із ними дані chromadb (sql-міграції сховища тощо).
from PyInstaller.utils.hooks import collect_data_files, collect_submodules

hiddenimports = collect_submodules("chromadb", filter=lambda n: ".test" not in n)
datas = collect_data_files("chromadb", excludes=["**/test/**"])
datas += collect_data_files("chromadb.utils.embedding_functions", include_py_files=True)
