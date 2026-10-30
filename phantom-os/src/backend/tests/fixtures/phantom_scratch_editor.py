"""Найменший текстовий редактор (GTK 3) для справжнього прогону тіла ПК.

Запускається системним python (`/usr/bin/python3`, там є `gi`), коли на машині
немає жодного звичного редактора (gedit, kate, mousepad…). Одне вікно, одне
поле тексту з фокусом; GTK сам викладає його в AT-SPI як роль «text».
"""
import sys

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import GLib, Gtk  # noqa: E402

GLib.set_prgname("phantom-smoke-editor")
GLib.set_application_name("phantom-smoke-editor")

window = Gtk.Window(title=sys.argv[1] if len(sys.argv) > 1 else "PHANTOM smoke editor")
window.set_default_size(640, 240)
view = Gtk.TextView()
view.set_wrap_mode(Gtk.WrapMode.WORD)
window.add(view)
window.connect("destroy", Gtk.main_quit)
window.show_all()
view.grab_focus()
GLib.timeout_add_seconds(60, Gtk.main_quit)  # ніколи не лишається жити після прогону
Gtk.main()
