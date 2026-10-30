import db as dbm


class _Rows:
    def __init__(self, rows):
        self._rows = rows

    def fetchall(self):
        return self._rows


class _SupabaseLike:
    """Supabase тримає власну auth.sessions з колонкою id поруч із нашою public.sessions."""

    def execute(self, sql, params=()):
        cols = {("public", "sessions"): ["token_hash", "account_id"], ("auth", "sessions"): ["id", "user_id"]}
        schemas = ["public"] if "current_schema()" in sql else ["public", "auth"]
        return _Rows([{"name": c} for s in schemas for c in cols[(s, params[0])]])


def test_колонки_беруться_лише_з_нашої_схеми_а_не_з_auth():
    store = dbm.Store.__new__(dbm.Store)
    store._pool = object()
    assert "id" not in store._columns(_SupabaseLike(), "sessions")
