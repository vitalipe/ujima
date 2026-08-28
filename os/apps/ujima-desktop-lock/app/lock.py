#!/usr/bin/env python3
"""UjimaOS lock screen — one chromeless GTK window in place of the chromium web app.
Same face as the page it replaces (desktop gradient, frost lock chip, live clock), for a
fraction of the memory: the browser cost ~335 MB across twelve processes, this is one.

No key handling and no X grab: Locked mode refuses the escape verbs, and the token stick
or a peer's unlock stops this scope. i3 sizes the window (Locked drops the bar gaps to 0),
so it never asks for fullscreen.

Typography is Pango's, colour is GTK CSS's — GTK3's CSS parser accepts font-weight and then
ignores it, so a weight stated there silently renders regular."""

import time

import gi
gi.require_version("Gtk", "3.0")
from gi.repository import Gtk, Gdk, GdkPixbuf, GLib, Pango

CLASS = "ujima-lock"     # WM_CLASS — how ujimad recognises this window (app.edn :window)
ICON = "app/lock.svg"    # cwd is the app dir: :exec runs as-authored
ICON_PX = 46
TICK_S = 10              # the face shows hours and minutes only

# The desktop's own wallpaper, already rasterized by the desktop stage: the lock wears
# whatever the desktop wears, so re-skinning wall.svg re-skins the lock with it. A CSS
# radial-gradient here bands visibly (GTK doesn't dither, the browser did); a missing
# file falls back to the flat base colour.
WALL = "file:///ujima/desktop/shell/wall.png"

CSS = ("""
.root {
  background-color: #14171b;
  background-image: url("%s");
  background-size: cover;
  background-position: center;
}
.chip {
  background-color: rgba(136,192,208,0.10);
  border: 1.5px solid rgba(136,192,208,0.28);
  border-radius: 28px;
  min-width: 96px; min-height: 96px;
}
.bright { color: #e7eaef; }
.faint  { color: #697180; }
""" % WALL).encode()


def label(text, px, weight, colour, features=None, spacing=None):
    """A styled label. The page's px sizes carry over unchanged — set_absolute_size takes
    device units, so 40px here is 40px there."""
    fd = Pango.FontDescription()
    fd.set_family("Public Sans")
    fd.set_weight(weight)
    fd.set_absolute_size(px * Pango.SCALE)

    attrs = Pango.AttrList()
    attrs.insert(Pango.attr_font_desc_new(fd))
    if features:
        attrs.insert(Pango.attr_font_features_new(features))
    if spacing:
        attrs.insert(Pango.attr_letter_spacing_new(spacing))

    lb = Gtk.Label(label=text)
    lb.get_style_context().add_class(colour)
    lb.set_attributes(attrs)
    return lb


chip = Gtk.Box(halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER)
chip.get_style_context().add_class("chip")
chip.set_center_widget(Gtk.Image.new_from_pixbuf(
    GdkPixbuf.Pixbuf.new_from_file_at_scale(ICON, ICON_PX, ICON_PX, True)))

clock = label("--:--", 22, Pango.Weight.SEMIBOLD, "bright", features="tnum=1")
date = label("", 13, Pango.Weight.MEDIUM, "faint", spacing=1200)   # ~1.2px of tracking

when = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
when.add(clock)
when.add(date)

card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=26,
               halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER)
card.add(chip)
card.add(label("Locked", 40, Pango.Weight.BOLD, "bright"))
card.add(when)

root = Gtk.Box()
root.get_style_context().add_class("root")
root.set_center_widget(card)


def tick():
    now = time.localtime()
    clock.set_text(time.strftime("%H:%M", now))
    date.set_text(time.strftime("%A, %B %-d", now).upper())
    return GLib.SOURCE_CONTINUE


def hide_cursor(w):
    w.get_window().set_cursor(Gdk.Cursor.new_from_name(w.get_display(), "none"))


provider = Gtk.CssProvider()
provider.load_from_data(CSS)
Gtk.StyleContext.add_provider_for_screen(Gdk.Screen.get_default(), provider,
                                         Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)

GLib.set_prgname(CLASS)
win = Gtk.Window()
win.set_wmclass(CLASS, CLASS)
win.set_title("Locked")
win.set_decorated(False)
win.set_default_size(1280, 720)
win.add(root)
win.connect("realize", hide_cursor)
win.connect("destroy", Gtk.main_quit)

tick()
GLib.timeout_add_seconds(TICK_S, tick)
win.show_all()
Gtk.main()
