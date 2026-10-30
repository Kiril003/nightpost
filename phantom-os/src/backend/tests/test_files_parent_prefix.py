"""«Вгору» у Файлах: батька показуємо, лише коли він справді всередині дозволеного дерева, а не просто має спільний префікс рядка."""
from tools import file_manager


def test_parent_sharing_only_a_string_prefix_is_not_exposed(tmp_path, monkeypatch):
    k, sub = tmp_path / "k", tmp_path / "kx" / "sub"
    k.mkdir()
    sub.mkdir(parents=True)
    monkeypatch.setattr(file_manager, "_allowed_roots", lambda: [k, sub])
    assert file_manager.list_dir_raw(path=str(sub))["parent"] is None


def test_parent_inside_the_allowed_tree_is_exposed(tmp_path, monkeypatch):
    k = tmp_path / "k"
    (k / "Docs").mkdir(parents=True)
    monkeypatch.setattr(file_manager, "_allowed_roots", lambda: [k])
    assert file_manager.list_dir_raw(path=str(k / "Docs"))["parent"] == str(k)
