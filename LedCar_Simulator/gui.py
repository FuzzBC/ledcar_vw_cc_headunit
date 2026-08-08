"""
LEDCAR-01 BLE simulator - GUI wrapper.

Starts/stops the built LedCar01Simulator.exe and shows its live output,
split into two resizable panes: a plain-English command log on top (with a
live state dashboard), and the exact raw console underneath. Pure stdlib
(tkinter) on purpose - no pip install required.
"""

import os
import queue
import re
import subprocess
import threading
import tkinter as tk
from tkinter import ttk

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
EXE_PATH = os.path.join(
    SCRIPT_DIR, "bin", "Release", "net8.0-windows10.0.19041.0", "LedCar01Simulator.exe"
)

BG = "#101014"
PANE_BG = "#08080a"
FG = "#e6e6e8"
MUTED = "#7a7a80"

PALETTE = {
    "banner": "#7f77dd",
    "advertising_ok": "#5dcaa5",
    "advertising_bad": "#e24b4a",
    "advertising_info": "#7a7a80",
    "noise": "#4a4a50",
    "power_on": "#5dcaa5",
    "power_off": "#e24b4a",
    "brightness": "#ef9f27",
    "mode": "#d4537e",
    "speed": "#378add",
    "direction": "#639922",
    "music": "#a68cff",
    "auth": "#5a5a60",
    "config": "#ef9f27",
    "warning": "#f0997b",
    "state": "#f5f5f5",
    "default": FG,
}

TS_RE = re.compile(r"^\[(\d\d:\d\d:\d\d\.\d\d\d)\]\s*(.*)$")


def zone_of(text: str) -> str:
    """DMX vs RGB vs BOTH zone for the "Where" column, sniffed from the raw meaning text.

    "LED tab" / "LED tab (sync)" is the protocol's single-packet flag that sets
    both zones at once (byte5=0x02 for brightness, byte1=0x01 for color) -
    distinct from "DMX zone" (DMX-only) and everything else (RGB-only).
    """
    if "LED tab" in text:
        return "BOTH"
    if "DMX" in text:
        return "DMX"
    if "music-reactive" in text:
        return "MUSIC"
    return "RGB"
STATE_RE = re.compile(
    r"lamp:\s*(ON|OFF)\s*color=\((\d+),(\d+),(\d+)\)\s*brightness=(\d+)%\s*"
    r"mode=(.*?)\s*speed=(\d+)%\s*dir=(\w+)\s*music=(\w+)"
)


def raw_tag(line: str):
    """Classify a raw console line for the bottom (verbatim) pane."""
    if "===" in line or line.strip() == "LEDCAR-01 BLE simulator":
        return "banner", None
    if "Advertising status: Started" in line:
        return "advertising_ok", None
    if "Advertising status: Aborted" in line or "Gave up retrying" in line:
        return "advertising_bad", None
    if "Advertising" in line or "Waiting for the phone" in line or "Note:" in line:
        return "advertising_info", None
    if "WriteRequested event fired" in line or "ReadRequested event fired" in line:
        return "noise", None
    if "POWER ON" in line:
        return "power_on", None
    if "POWER OFF" in line:
        return "power_off", None
    if "BRIGHTNESS" in line:
        return "brightness", None
    if "MODE id=" in line:
        return "mode", None
    if "SPEED" in line:
        return "speed", None
    if "DIRECTION" in line:
        return "direction", None
    if "MUSIC MODE" in line:
        return "music", None
    if "AUTH/PASSWORD" in line:
        return "auth", None
    if "CONFIG" in line:
        return "config", None
    if "unrecognised" in line or "UNKNOWN" in line or "not yet mapped" in line:
        return "warning", None
    if line.strip().startswith("lamp:"):
        return "state", None
    if "COLOR" in line:
        m = re.search(r"r=(\d+)\s+g=(\d+)\s+b=(\d+)", line)
        if m:
            r, g, b = (int(x) for x in m.groups())
            return "literal", f"#{r:02x}{g:02x}{b:02x}"
    return "default", None


def humanize(line: str):
    """Turn a raw console line into a plain-English (tag, text) pair, or None to skip."""
    m = TS_RE.match(line.strip())
    msg = m.group(2) if m else line.strip()

    if msg.startswith("RX ["):
        after = msg.split("->", 1)[1].strip() if "->" in msg else msg[msg.find("("):].strip()

        mm = re.search(r"POWER (ON|OFF)\s*[-—]\s*(.+)", after)
        if mm:
            tag = "power_on" if mm.group(1) == "ON" else "power_off"
            return tag, f"P:{zone_of(mm.group(2))}", f"Power received — {mm.group(1)}"
        if after.startswith("POWER ON"):
            return "power_on", "P:RGB", "Power received — ON"
        if after.startswith("POWER OFF"):
            return "power_off", "P:RGB", "Power received — OFF"

        mm = re.search(r"COLOR \(plain RGB tab\) r=(\d+) g=(\d+) b=(\d+)", after)
        if mm:
            r, g, b = (int(x) for x in mm.groups())
            text = f"Color received \u2014 r={r} g={g} b={b}"
            return ("literal", f"#{r:02x}{g:02x}{b:02x}"), "C:RGB", text
        mm = re.search(r"COLOR r=(\d+) g=(\d+) b=(\d+)\s*[-\u2014]\s*(.+)", after)
        if mm:
            r, g, b = (int(x) for x in mm.groups()[:3])
            text = f"Color received \u2014 r={r} g={g} b={b}"
            return ("literal", f"#{r:02x}{g:02x}{b:02x}"), f"C:{zone_of(mm.group(4))}", text

        mm = re.search(r"BRIGHTNESS \(plain RGB tab\) (\d+)%", after)
        if mm:
            return "brightness", "B:RGB", f"Brightness received — {mm.group(1)}%"
        mm = re.search(r"BRIGHTNESS (\d+)%\s*[-—]\s*(.+)", after)
        if mm:
            return "brightness", f"B:{zone_of(mm.group(2))}", f"Brightness received — {mm.group(1)}%"
        mm = re.search(r"BRIGHTNESS (\d+)%", after)
        if mm:
            return "brightness", "B:RGB", f"Brightness received — {mm.group(1)}%"

        mm = re.search(r"MODE \((.+?)\) id=\d+ \((.+)\)", after)
        if mm:
            return "mode", f"M:{zone_of(mm.group(1))}", f"Mode received \u2014 {mm.group(2)}"
        mm = re.search(r"MODE id=\d+ \((.+)\)", after)
        if mm:
            return "mode", "M:RGB", f"Mode received \u2014 {mm.group(1)}"

        mm = re.search(r"SPEED \((.+?)\) (\d+)%", after)
        if mm:
            return "speed", f"S:{zone_of(mm.group(1))}", f"Speed received \u2014 {mm.group(2)}%"
        mm = re.search(r"SPEED (\d+)%", after)
        if mm:
            return "speed", "S:RGB", f"Speed received \u2014 {mm.group(1)}%"

        mm = re.search(r"DIRECTION (\w+)", after)
        if mm:
            return "direction", "D:DMX", f"Direction received \u2014 {mm.group(1)}"

        mm = re.search(r"MUSIC MODE (\w+)", after)
        if mm:
            return "music", "MU:DMX", f"Music received \u2014 {mm.group(1)}"

        if after.startswith("AUTH/PASSWORD"):
            return "auth", "CONN", "Auth handshake received"

        mm = re.search(r"WELCOME mode (\w+)", after)
        if mm:
            return "config", "CFG:RGB", f"Welcome mode received \u2014 {mm.group(1)}"

        mm = re.search(r"SPI CONFIG\s+pixels=(\d+)\s+colorOrder=(\S+)", after)
        if mm:
            return "config", "CFG:DMX", f"SPI config received \u2014 {mm.group(1)} pixels, color order {mm.group(2)}"

        if "CONFIG" in after:
            return "config", "CFG:DMX", "Config received"

        if "unrecognised" in after or "not yet mapped" in after:
            return "warning", "??", "Unrecognised command received \u2014 see raw console"

        return "default", "??", after

    if "Notification subscribers:" in msg:
        mm = re.search(r"Notification subscribers: (\d+)", msg)
        if mm:
            n = int(mm.group(1))
            return ("power_on" if n > 0 else "power_off", "CONN",
                    "Phone connected and is listening for updates" if n > 0 else "Phone disconnected")

    if "Advertising status: Started" in msg:
        return "advertising_ok", "CONN", "Simulator is advertising \u2014 ready for a phone to connect"
    if "Gave up retrying" in msg:
        return "advertising_bad", "CONN", "Advertising failed to start \u2014 see raw console"

    return None


class SimulatorGui:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.proc: subprocess.Popen | None = None
        self.reader_thread: threading.Thread | None = None
        self.line_queue: "queue.Queue[str | None]" = queue.Queue()

        root.title("LEDCAR-01 BLE simulator")
        root.configure(bg=BG)
        root.geometry("900x680")

        self._build_toolbar()
        self._build_panes()

        root.protocol("WM_DELETE_WINDOW", self.on_close)
        self.poll_queue()

    def _build_toolbar(self):
        toolbar = tk.Frame(self.root, bg=BG)
        toolbar.pack(fill="x", padx=12, pady=10)

        self.start_btn = ttk.Button(toolbar, text="Start", command=self.start)
        self.start_btn.pack(side="left")

        self.stop_btn = ttk.Button(toolbar, text="Stop", command=self.stop, state="disabled")
        self.stop_btn.pack(side="left", padx=(8, 0))

        self.status_var = tk.StringVar(value="Stopped")
        self.status_label = tk.Label(
            toolbar, textvariable=self.status_var, bg=BG, fg=PALETTE["power_off"],
            font=("Segoe UI", 10, "bold"),
        )
        self.status_label.pack(side="right")

    def _build_panes(self):
        paned = ttk.PanedWindow(self.root, orient="vertical")
        paned.pack(fill="both", expand=True, padx=12, pady=(0, 12))

        top = tk.Frame(paned, bg=BG)
        bottom = tk.Frame(paned, bg=BG)
        paned.add(top, weight=1)
        paned.add(bottom, weight=1)

        self._build_pane_header(top, "Command details",
                                 lambda: self.human_tree.delete(*self.human_tree.get_children()))
        self._build_dashboard(top)
        self.human_tree = self._make_tree(top)

        self._build_pane_header(bottom, "Raw console", lambda: self.raw_console.delete("1.0", "end"), top_pad=8)
        self.raw_console = self._make_text(bottom)

    def _build_pane_header(self, parent, title: str, on_clear, top_pad: int = 0):
        header = tk.Frame(parent, bg=BG)
        header.pack(fill="x", pady=(top_pad, 4))
        tk.Label(header, text=title, bg=BG, fg=MUTED,
                 font=("Segoe UI", 9, "bold")).pack(side="left")
        ttk.Button(header, text="Clear", command=on_clear).pack(side="right")

    def _build_dashboard(self, parent):
        bar = tk.Frame(parent, bg="#1c1c22", padx=10, pady=8)
        bar.pack(fill="x", pady=(0, 8))

        self.swatch = tk.Canvas(bar, width=22, height=22, bg="#333338",
                                 highlightthickness=1, highlightbackground="#33333a")
        self.swatch.pack(side="left")

        self.dashboard_var = tk.StringVar(value="No data yet \u2014 press Start and connect from the app.")
        tk.Label(bar, textvariable=self.dashboard_var, bg="#1c1c22", fg=FG,
                 font=("Segoe UI", 10), anchor="w", justify="left").pack(
            side="left", padx=(10, 0), fill="x", expand=True)

    def _make_text(self, parent) -> tk.Text:
        widget = tk.Text(
            parent, bg=PANE_BG, fg=FG, insertbackground=FG,
            font=("Cascadia Mono", 10), wrap="word", borderwidth=0,
            highlightthickness=0, padx=10, pady=8,
        )
        widget.pack(fill="both", expand=True)
        for tag, color in PALETTE.items():
            widget.tag_configure(tag, foreground=color)
        return widget

    def _make_tree(self, parent) -> ttk.Treeview:
        style = ttk.Style(self.root)
        style.configure("Log.Treeview", background=PANE_BG, foreground=FG,
                         fieldbackground=PANE_BG, borderwidth=0, rowheight=24,
                         font=("Segoe UI", 10))
        style.configure("Log.Treeview.Heading", background="#1c1c22", foreground=MUTED,
                         font=("Segoe UI", 9, "bold"), relief="flat")
        style.map("Log.Treeview", background=[("selected", "#2a2a30")])

        container = tk.Frame(parent, bg=PANE_BG)
        container.pack(fill="both", expand=True)

        tree = ttk.Treeview(
            container, columns=("time", "where", "what"), show="headings",
            style="Log.Treeview", selectmode="extended",
        )
        tree.heading("time", text="Time")
        tree.heading("where", text="Where")
        tree.heading("what", text="What happened")
        tree.column("time", width=90, minwidth=70, anchor="w", stretch=False)
        tree.column("where", width=70, minwidth=55, anchor="w", stretch=False)
        tree.column("what", width=500, minwidth=200, anchor="w", stretch=True)

        vsb = ttk.Scrollbar(container, orient="vertical", command=tree.yview)
        tree.configure(yscrollcommand=vsb.set)
        tree.pack(side="left", fill="both", expand=True)
        vsb.pack(side="right", fill="y")

        for tag, color in PALETTE.items():
            tree.tag_configure(tag, foreground=color)

        self._wire_tree_copy(tree)
        return tree

    def _wire_tree_copy(self, tree: ttk.Treeview):
        """Selecting rows alone doesn't put text on the clipboard in a Treeview
        the way it does in a Text widget - wire up Ctrl+C and a right-click
        menu so the log stays copy/pasteable."""

        def copy_items(item_ids):
            if not item_ids:
                return
            lines = ["\t".join(str(v) for v in tree.item(i, "values")) for i in item_ids]
            self.root.clipboard_clear()
            self.root.clipboard_append("\n".join(lines))

        def copy_selected(_event=None):
            copy_items(tree.selection())

        def copy_all(_event=None):
            copy_items(tree.get_children())

        def show_menu(event):
            row = tree.identify_row(event.y)
            if row and row not in tree.selection():
                tree.selection_set(row)
            menu = tk.Menu(tree, tearoff=0)
            menu.add_command(label="Copy selected row(s)", command=copy_selected)
            menu.add_command(label="Copy all rows", command=copy_all)
            menu.tk_popup(event.x_root, event.y_root)

        tree.bind("<Control-c>", copy_selected)
        tree.bind("<Control-C>", copy_selected)
        tree.bind("<Button-3>", show_menu)

    def _insert(self, widget: tk.Text, tag, text: str):
        if isinstance(tag, tuple):
            kind, literal = tag
            tag_name = f"literal_{literal}"
            widget.tag_configure(tag_name, foreground=literal)
        else:
            tag_name = tag
        widget.insert("end", text, (tag_name,))
        widget.see("end")

    def _insert_row(self, ts: str, tag, where: str, text: str):
        if isinstance(tag, tuple):
            _, literal = tag
            tag_name = f"literal_{literal}"
            self.human_tree.tag_configure(tag_name, foreground=literal)
        else:
            tag_name = tag
        item = self.human_tree.insert("", "end", values=(ts, where, text), tags=(tag_name,))
        self.human_tree.see(item)

    def append_line(self, line: str):
        tag, _ = raw_tag(line)
        self._insert(self.raw_console, tag, line)

        if line.strip().startswith("lamp:"):
            self.update_dashboard(line)
            return

        m = TS_RE.match(line.strip())
        ts = m.group(1) if m else ""

        result = humanize(line)
        if result:
            tag, where, text = result
            self._insert_row(ts, tag, where, text)

    def update_dashboard(self, line: str):
        m = STATE_RE.search(line)
        if not m:
            return
        on, r, g, b, brightness, mode, speed, direction, music = m.groups()
        color_hex = f"#{int(r):02x}{int(g):02x}{int(b):02x}"
        self.swatch.configure(bg=color_hex)
        state_text = (
            f"Power: {on}   \u00b7   RGB({r}, {g}, {b})   \u00b7   Brightness {brightness}%   \u00b7   "
            f"Mode: {mode}   \u00b7   Speed {speed}%   \u00b7   Direction: {direction}   \u00b7   Music: {music}"
        )
        self.dashboard_var.set(state_text)

    def start(self):
        if self.proc is not None:
            return
        subprocess.run(["taskkill", "/F", "/IM", "LedCar01Simulator.exe"], capture_output=True)

        if not os.path.isfile(EXE_PATH):
            self._insert(self.raw_console, "warning", f"Build not found at {EXE_PATH}\nRun: dotnet build -c Release\n")
            return

        self.proc = subprocess.Popen(
            [EXE_PATH],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
            cwd=SCRIPT_DIR,
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
        self.reader_thread = threading.Thread(target=self._read_output, daemon=True)
        self.reader_thread.start()

        self.status_var.set("Running")
        self.status_label.configure(fg=PALETTE["power_on"])
        self.start_btn.configure(state="disabled")
        self.stop_btn.configure(state="normal")

    def _read_output(self):
        assert self.proc is not None and self.proc.stdout is not None
        for line in self.proc.stdout:
            self.line_queue.put(line)
        self.line_queue.put(None)

    def stop(self):
        if self.proc is None:
            return
        try:
            self.proc.terminate()
        except Exception:
            pass
        subprocess.run(["taskkill", "/F", "/IM", "LedCar01Simulator.exe"], capture_output=True)
        self.proc = None
        self.status_var.set("Stopped")
        self.status_label.configure(fg=PALETTE["power_off"])
        self.start_btn.configure(state="normal")
        self.stop_btn.configure(state="disabled")

    def poll_queue(self):
        try:
            while True:
                line = self.line_queue.get_nowait()
                if line is None:
                    if self.proc is not None:
                        self.stop()
                    break
                self.append_line(line)
        except queue.Empty:
            pass
        self.root.after(60, self.poll_queue)

    def on_close(self):
        self.stop()
        self.root.destroy()


def main():
    root = tk.Tk()
    style = ttk.Style(root)
    try:
        style.theme_use("clam")
    except tk.TclError:
        pass
    style.configure("TButton", padding=6)
    SimulatorGui(root)
    root.mainloop()


if __name__ == "__main__":
    main()
