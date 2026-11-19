"""Tkinter desktop UI for BookTrans."""

from __future__ import annotations

import math
import os
import queue
import subprocess
import sys
import threading
import traceback
import webbrowser
from pathlib import Path
from tkinter import colorchooser, filedialog, messagebox, ttk
import tkinter as tk

from . import APP_NAME, __version__
from .config import PRESETS, Connection, load_settings, save_settings
from .epub_io import EpubError, export_book, placeholders_in, plain_text
from .llm import AuthError, ChatClient, LLMError, QuotaExhausted, Stopped
from .pipeline import Pipeline
from .project import STATUS_LABEL, TRANSLATED, Project

UI_FONT = ("Segoe UI", 10)
TEXT_FONT = ("Cambria", 12)
STATUS_COLORS = {"pending": "#888888", "draft": "#9a6b00", "reviewed": "#1e7b34", "edited": "#1f5fa8",
                 "error": "#b3261e"}


def _scrolled_text(parent, **kw) -> tk.Text:
    frame = ttk.Frame(parent)
    txt = tk.Text(frame, wrap="word", undo=True, font=TEXT_FONT, relief="flat", borderwidth=6, **kw)
    sb = ttk.Scrollbar(frame, orient="vertical", command=txt.yview)
    txt.configure(yscrollcommand=sb.set)
    txt.pack(side="left", fill="both", expand=True)
    sb.pack(side="right", fill="y")
    txt.frame = frame  # type: ignore[attr-defined]
    return txt


def _tree(parent, columns: list[tuple[str, str, int]], **kw) -> ttk.Treeview:
    frame = ttk.Frame(parent)
    tv = ttk.Treeview(frame, columns=[c[0] for c in columns], show="headings", **kw)
    for cid, title, width in columns:
        tv.heading(cid, text=title, anchor="w")
        tv.column(cid, width=width, anchor="w", stretch=width > 120)
    sb = ttk.Scrollbar(frame, orient="vertical", command=tv.yview)
    tv.configure(yscrollcommand=sb.set)
    tv.pack(side="left", fill="both", expand=True)
    sb.pack(side="right", fill="y")
    tv.frame = frame  # type: ignore[attr-defined]
    return tv


# =============================================================================== app


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(f"{APP_NAME} {__version__} – Dịch sách Anh → Việt & tạo sách song ngữ")
        self.geometry("1360x860")
        self.minsize(1000, 640)
        self.option_add("*Font", UI_FONT)
        style = ttk.Style(self)
        if "vista" in style.theme_names():
            style.theme_use("vista")
        style.configure("Treeview", rowheight=24)
        style.configure("Accent.TButton", font=("Segoe UI Semibold", 10))
        style.configure("Title.TLabel", font=("Segoe UI Semibold", 12))

        self.settings = load_settings()
        self.project: Project | None = None
        self.book = None
        self.q: queue.Queue = queue.Queue()
        self.worker: threading.Thread | None = None
        self.stop_event = threading.Event()

        top = ttk.Frame(self, padding=(10, 8))
        top.pack(fill="x")
        ttk.Button(top, text="📖  Mở sách EPUB…", style="Accent.TButton", command=self.open_epub).pack(side="left")
        self.book_label = ttk.Label(top, text="Chưa mở sách nào", style="Title.TLabel")
        self.book_label.pack(side="left", padx=14)
        self.stats_label = ttk.Label(top, text="", foreground="#555")
        self.stats_label.pack(side="right")

        self.nb = ttk.Notebook(self)
        self.nb.pack(fill="both", expand=True, padx=8)
        self.settings_tab = SettingsTab(self.nb, self)
        self.profile_tab = ProfileTab(self.nb, self)
        self.translate_tab = TranslateTab(self.nb, self)
        self.export_tab = ExportTab(self.nb, self)
        self.nb.add(self.settings_tab, text="  1. Cài đặt model  ")
        self.nb.add(self.profile_tab, text="  2. Hồ sơ tác phẩm  ")
        self.nb.add(self.translate_tab, text="  3. Dịch & biên tập  ")
        self.nb.add(self.export_tab, text="  4. Xuất sách  ")
        self.nb.bind("<<NotebookTabChanged>>", lambda e: self.profile_tab.save())

        bottom = ttk.Frame(self, padding=(10, 4))
        bottom.pack(fill="x")
        self.progress = ttk.Progressbar(bottom, length=260, mode="determinate")
        self.progress.pack(side="right")
        self.status = ttk.Label(bottom, text="Sẵn sàng")
        self.status.pack(side="left", fill="x", expand=True)

        self.protocol("WM_DELETE_WINDOW", self.on_close)
        self.after(100, self._poll)
        if self.settings.last_epub and Path(self.settings.last_epub).exists():
            self.after(200, lambda: self.load_epub(self.settings.last_epub))
        elif not any(c.api_key for c in self.settings.connections):
            self.nb.select(0)

    # ------------------------------------------------------------------ project
    def open_epub(self):
        path = filedialog.askopenfilename(title="Chọn sách EPUB tiếng Anh", filetypes=[("EPUB", "*.epub")])
        if path:
            self.load_epub(path)

    def load_epub(self, path: str):
        if self.busy():
            messagebox.showinfo(APP_NAME, "Đang chạy tác vụ, hãy dừng trước khi mở sách khác.")
            return
        self.config(cursor="watch")
        self.update_idletasks()
        try:
            if self.project:
                self.profile_tab.save()
                self.project.close()
            self.project, self.book = Project.open_for_epub(path)
        except EpubError as e:
            messagebox.showerror(APP_NAME, str(e))
            return
        except Exception as e:  # noqa: BLE001
            messagebox.showerror(APP_NAME, f"Không mở được sách:\n{e}")
            traceback.print_exc()
            return
        finally:
            self.config(cursor="")
        self.settings.last_epub = path
        save_settings(self.settings)
        self.book_label.config(text=f"{self.book.title}")
        self.profile_tab.load()
        self.translate_tab.load()
        self.export_tab.load()
        self.refresh_stats()
        self.set_status(f"Đã mở: {path}  (tiến độ lưu tại {self.project.db_path.name})")
        if not self.project.get_meta("brief"):
            self.nb.select(self.profile_tab)

    def refresh_stats(self):
        if not self.project:
            return
        st = self.project.stats()
        total = sum(c for c, _ in st.values())
        done = sum(st.get(s, (0, 0))[0] for s in TRANSLATED)
        chars = sum(ch for _, ch in st.values())
        pct = 100 * done / total if total else 0
        self.stats_label.config(text=f"{done:,}/{total:,} đoạn đã dịch ({pct:.0f}%) · {chars:,} ký tự gốc")

    def require_project(self) -> bool:
        if not self.project:
            messagebox.showinfo(APP_NAME, "Hãy mở một sách EPUB trước.")
            return False
        return True

    # ------------------------------------------------------------------- worker
    def busy(self) -> bool:
        return self.worker is not None and self.worker.is_alive()

    def run_task(self, name: str, fn, on_done=None):
        """Run ``fn(pipeline)`` in a background thread. ``on_done(result)`` runs on the UI thread."""
        if self.busy():
            messagebox.showinfo(APP_NAME, "Một tác vụ khác đang chạy.")
            return
        self.profile_tab.save()
        save_settings(self.settings)
        self.stop_event = threading.Event()
        q = self.q

        def target():
            pipe = Pipeline(self.project, self.settings, log=lambda m: q.put(("log", m)),
                            progress=lambda d, t: q.put(("progress", d, t)),
                            on_unit=lambda uid: q.put(("unit", uid)), stop=self.stop_event)
            try:
                res = fn(pipe)
                q.put(("done", name, res, None, on_done))
            except BaseException as e:  # noqa: BLE001
                q.put(("done", name, None, e, on_done))
            finally:
                pipe.close()

        self.worker = threading.Thread(target=target, daemon=True)
        self.worker.start()
        self.translate_tab.set_running(True)
        self.set_status(f"{name}…")

    def stop(self):
        if self.busy():
            self.stop_event.set()
            self.set_status("Đang dừng… (chờ yêu cầu hiện tại kết thúc)")

    def _poll(self):
        try:
            for _ in range(200):
                msg = self.q.get_nowait()
                kind = msg[0]
                if kind == "log":
                    self.translate_tab.log(msg[1])
                    self.set_status(msg[1])
                elif kind == "progress":
                    d, t = msg[1], msg[2]
                    self.progress.config(maximum=max(t, 1), value=d)
                    self.translate_tab.refresh_docs()
                    self.refresh_stats()
                elif kind == "unit":
                    self.translate_tab.refresh_unit(msg[1])
                elif kind == "done":
                    self._finish(*msg[1:])
        except queue.Empty:
            pass
        self.after(120, self._poll)

    def _finish(self, name, res, err, on_done):
        self.translate_tab.set_running(False)
        self.translate_tab.refresh_docs()
        self.refresh_stats()
        if err is None:
            self.set_status(f"✓ {name}: xong")
            if on_done:
                on_done(res)
        elif isinstance(err, Stopped):
            self.translate_tab.log("■ Đã dừng. Tiến độ đã được lưu.")
            self.set_status("Đã dừng – tiến độ đã lưu")
        elif isinstance(err, QuotaExhausted):
            self.translate_tab.log(f"⏸ {err}")
            messagebox.showwarning(APP_NAME, str(err))
        elif isinstance(err, AuthError):
            messagebox.showerror(APP_NAME, f"Lỗi API key:\n{err}")
        elif isinstance(err, (LLMError, EpubError)):
            self.translate_tab.log(f"✗ {err}")
            messagebox.showerror(APP_NAME, str(err))
        else:
            self.translate_tab.log(f"✗ Lỗi: {err!r}")
            traceback.print_exception(err)
            messagebox.showerror(APP_NAME, f"Lỗi không mong muốn:\n{err!r}")

    def set_status(self, text: str):
        self.status.config(text=text[:200])

    def on_close(self):
        if self.busy():
            if not messagebox.askyesno(APP_NAME, "Đang dịch. Dừng và thoát? (Tiến độ đã dịch vẫn được lưu)"):
                return
            self.stop_event.set()
            self.worker.join(timeout=3)
        try:
            self.profile_tab.save()
            save_settings(self.settings)
        finally:
            self.destroy()


# ========================================================================= settings


class SettingsTab(ttk.Frame):
    def __init__(self, master, app: App):
        super().__init__(master, padding=12)
        self.app = app
        self.s = app.settings
        self.current: Connection | None = None
        self._loading = False

        ttk.Label(self, text="Kết nối model (thứ tự = ưu tiên; hết quota sẽ tự chuyển xuống model kế tiếp)",
                  style="Title.TLabel").grid(row=0, column=0, columnspan=2, sticky="w", pady=(0, 8))

        left = ttk.Frame(self)
        left.grid(row=1, column=0, sticky="nsw", padx=(0, 16))
        self.listbox = tk.Listbox(left, height=12, width=34, exportselection=False, activestyle="none")
        self.listbox.pack(fill="y", expand=True)
        self.listbox.bind("<<ListboxSelect>>", lambda e: self._select())
        btns = ttk.Frame(left)
        btns.pack(fill="x", pady=6)
        ttk.Button(btns, text="＋ Thêm", width=8, command=self._add).pack(side="left")
        ttk.Button(btns, text="－ Xoá", width=7, command=self._remove).pack(side="left", padx=4)
        ttk.Button(btns, text="↑", width=3, command=lambda: self._move(-1)).pack(side="left")
        ttk.Button(btns, text="↓", width=3, command=lambda: self._move(1)).pack(side="left", padx=4)

        form = ttk.Frame(self)
        form.grid(row=1, column=1, sticky="nsew")
        self.columnconfigure(1, weight=1)
        self.v = {k: tk.StringVar() for k in ("name", "preset", "base_url", "api_key", "model", "rpm")}
        self.b = {k: tk.BooleanVar() for k in ("enabled", "translate", "review")}
        r = 0

        def row(label, widget, extra=None):
            nonlocal r
            ttk.Label(form, text=label).grid(row=r, column=0, sticky="w", pady=4, padx=(0, 8))
            widget.grid(row=r, column=1, sticky="ew", pady=4)
            if extra:
                extra.grid(row=r, column=2, sticky="w", padx=6)
            r += 1

        form.columnconfigure(1, weight=1)
        row("Tên hiển thị", ttk.Entry(form, textvariable=self.v["name"]))
        self.preset_cb = ttk.Combobox(form, textvariable=self.v["preset"], values=list(PRESETS), state="readonly")
        self.preset_cb.bind("<<ComboboxSelected>>", lambda e: self._preset_changed())
        row("Nhà cung cấp", self.preset_cb)
        row("Base URL", ttk.Entry(form, textvariable=self.v["base_url"]))
        key_entry = ttk.Entry(form, textvariable=self.v["api_key"], show="•")
        row("API key", key_entry, ttk.Button(form, text="Lấy key miễn phí ↗", command=self._open_key_url))
        self.model_cb = ttk.Combobox(form, textvariable=self.v["model"], values=[])
        row("Model", self.model_cb, ttk.Button(form, text="Tải danh sách model", command=self._list_models))
        row("Giới hạn yêu cầu/phút", ttk.Spinbox(form, from_=0, to=1000, textvariable=self.v["rpm"], width=8),
            ttk.Label(form, text="(0 = không giới hạn; đặt thấp hơn hạn mức free để tránh lỗi 429)", foreground="#666"))
        checks = ttk.Frame(form)
        ttk.Checkbutton(checks, text="Bật", variable=self.b["enabled"]).pack(side="left")
        ttk.Checkbutton(checks, text="Dùng để dịch", variable=self.b["translate"]).pack(side="left", padx=12)
        ttk.Checkbutton(checks, text="Dùng để biên tập (lượt 2)", variable=self.b["review"]).pack(side="left")
        row("Vai trò", checks)
        test_row = ttk.Frame(form)
        ttk.Button(test_row, text="Kiểm tra kết nối (dịch thử 1 câu)", command=self._test).pack(side="left")
        self.test_label = ttk.Label(test_row, text="", foreground="#333", wraplength=620)
        self.test_label.pack(side="left", padx=10)
        row("", test_row)
        for var in list(self.v.values()) + list(self.b.values()):
            var.trace_add("write", lambda *a: self._write_back())

        opts = ttk.LabelFrame(self, text="Tuỳ chọn dịch", padding=10)
        opts.grid(row=2, column=0, columnspan=2, sticky="ew", pady=(16, 0))
        self.o = {
            "chunk_chars": tk.IntVar(value=self.s.chunk_chars),
            "context_paragraphs": tk.IntVar(value=self.s.context_paragraphs),
            "temperature": tk.DoubleVar(value=self.s.temperature),
            "analysis_sample_chars": tk.IntVar(value=self.s.analysis_sample_chars),
        }
        items = [
            ("Số ký tự gốc mỗi lần gửi", "chunk_chars", 500, 20000, 500,
             "lớn → ít yêu cầu hơn (tiết kiệm quota); nhỏ → model tập trung hơn. 2500–5000 là hợp lý"),
            ("Số đoạn ngữ cảnh trước đó", "context_paragraphs", 0, 20, 1,
             "giúp giữ giọng văn và xưng hô liền mạch giữa các lần gửi"),
            ("Nhiệt độ (temperature)", "temperature", 0.0, 1.0, 0.1, "0.3–0.5 cho văn chương: đủ linh hoạt mà vẫn sát nghĩa"),
            ("Số ký tự mẫu để phân tích", "analysis_sample_chars", 10000, 600000, 10000,
             "Gemini nhận được rất dài; model local nên để ≤ 30.000"),
        ]
        for i, (label, key, lo, hi, step, hint) in enumerate(items):
            ttk.Label(opts, text=label).grid(row=i, column=0, sticky="w", pady=3)
            ttk.Spinbox(opts, from_=lo, to=hi, increment=step, textvariable=self.o[key], width=10).grid(
                row=i, column=1, sticky="w", padx=8)
            ttk.Label(opts, text=hint, foreground="#666").grid(row=i, column=2, sticky="w")
        ttk.Button(opts, text="💾 Lưu cài đặt", command=self.save).grid(row=0, column=3, rowspan=2, padx=20)
        ttk.Label(self, text=f"File cài đặt (gồm API key, chỉ lưu trên máy này): {Path(os.environ.get('APPDATA', '~')) / 'BookTrans' / 'settings.json'}",
                  foreground="#888").grid(row=3, column=0, columnspan=2, sticky="w", pady=(10, 0))
        self._refresh_list()

    def save(self):
        for k, var in self.o.items():
            try:
                setattr(self.s, k, var.get())
            except tk.TclError:
                pass
        save_settings(self.s)
        self.app.set_status("Đã lưu cài đặt")
        self.app.translate_tab.update_estimate()

    def _refresh_list(self, select: int | None = None):
        self.listbox.delete(0, "end")
        for c in self.s.connections:
            roles = "+".join(x for x, on in (("dịch", c.translate), ("biên tập", c.review)) if on)
            mark = "" if c.enabled else " (tắt)"
            self.listbox.insert("end", f"{c.name} · {c.model or '?'} [{roles}]{mark}")
        if self.s.connections:
            idx = 0 if select is None else max(0, min(select, len(self.s.connections) - 1))
            self.listbox.selection_clear(0, "end")
            self.listbox.selection_set(idx)
            self._select()

    def _select(self):
        sel = self.listbox.curselection()
        if not sel:
            return
        self.current = self.s.connections[sel[0]]
        c = self.current
        self._loading = True
        for k in ("name", "preset", "base_url", "api_key", "model"):
            self.v[k].set(getattr(c, k))
        self.v["rpm"].set(str(c.rpm))
        for k in self.b:
            self.b[k].set(getattr(c, k))
        self.model_cb.config(values=[c.model] if c.model else [])
        self.test_label.config(text="")
        self._loading = False

    def _write_back(self):
        if self._loading or not self.current:
            return
        c = self.current
        for k in ("name", "preset", "base_url", "api_key", "model"):
            setattr(c, k, self.v[k].get().strip())
        try:
            c.rpm = int(self.v["rpm"].get() or 0)
        except ValueError:
            pass
        for k in self.b:
            setattr(c, k, self.b[k].get())
        idx = self.s.connections.index(c)
        roles = "+".join(x for x, on in (("dịch", c.translate), ("biên tập", c.review)) if on)
        self.listbox.delete(idx)
        self.listbox.insert(idx, f"{c.name} · {c.model or '?'} [{roles}]" + ("" if c.enabled else " (tắt)"))
        self.listbox.selection_set(idx)

    def _preset_changed(self):
        p = PRESETS[self.v["preset"].get()]
        self.v["base_url"].set(p["base_url"])
        self.v["model"].set(p["model"])
        self.v["rpm"].set(str(p["rpm"]))
        self.v["name"].set(self.v["preset"].get().split(" (")[0])

    def _add(self):
        win = tk.Toplevel(self)
        win.title("Thêm kết nối")
        win.transient(self)
        ttk.Label(win, text="Chọn nhà cung cấp:", padding=10).pack(anchor="w")
        var = tk.StringVar(value=list(PRESETS)[0])
        for name in PRESETS:
            ttk.Radiobutton(win, text=name, value=name, variable=var).pack(anchor="w", padx=20)

        def ok():
            self.s.connections.append(Connection.from_preset(var.get()))
            win.destroy()
            self._refresh_list(len(self.s.connections) - 1)

        ttk.Button(win, text="Thêm", command=ok).pack(pady=10)
        win.grab_set()

    def _remove(self):
        if self.current and messagebox.askyesno(APP_NAME, f"Xoá kết nối '{self.current.name}'?"):
            self.s.connections.remove(self.current)
            self.current = None
            self._refresh_list()

    def _move(self, d: int):
        if not self.current:
            return
        i = self.s.connections.index(self.current)
        j = i + d
        if 0 <= j < len(self.s.connections):
            cs = self.s.connections
            cs[i], cs[j] = cs[j], cs[i]
            self._refresh_list(j)

    def _open_key_url(self):
        p = PRESETS.get(self.v["preset"].get(), {})
        if p.get("key_url"):
            webbrowser.open(p["key_url"])

    def _bg(self, fn, done):
        conn = Connection(**{**self.current.__dict__}) if self.current else None
        if not conn:
            return
        self.test_label.config(text="Đang kết nối…", foreground="#333")

        def run():
            try:
                res = fn(conn)
                self.after(0, lambda: done(res, None))
            except Exception as e:  # noqa: BLE001
                self.after(0, lambda e=e: done(None, e))

        threading.Thread(target=run, daemon=True).start()

    def _list_models(self):
        def fn(conn):
            c = ChatClient(conn, timeout=30, max_retries=1)
            try:
                return c.list_models()
            finally:
                c.close()

        def done(models, err):
            if err:
                self.test_label.config(text=f"✗ {err}", foreground="#b3261e")
                return
            self.model_cb.config(values=models)
            self.test_label.config(text=f"✓ {len(models)} model. Mở ô Model để chọn.", foreground="#1e7b34")

        self._bg(fn, done)

    def _test(self):
        def fn(conn):
            c = ChatClient(conn, timeout=120, max_retries=1)
            try:
                return c.chat("You are a literary translator. Translate English into Vietnamese. Reply with the translation only.",
                              "\u201cDon\u2019t be daft,\u201d she said, not unkindly. \u201cThe tide waits for no one, least of all you.\u201d",
                              temperature=0.3)
            finally:
                c.close()

        def done(res, err):
            if err:
                self.test_label.config(text=f"✗ {err}", foreground="#b3261e")
            else:
                self.test_label.config(text=f"✓ {res.model}: {res.text[:300]}", foreground="#1e7b34")

        self._bg(fn, done)


# ========================================================================== profile


class ProfileTab(ttk.Frame):
    def __init__(self, master, app: App):
        super().__init__(master, padding=10)
        self.app = app
        bar = ttk.Frame(self)
        bar.pack(fill="x")
        ttk.Button(bar, text="🔍  Phân tích tác phẩm bằng AI", style="Accent.TButton", command=self.analyze).pack(side="left")
        ttk.Button(bar, text="💾 Lưu", command=self.save).pack(side="left", padx=8)
        ttk.Label(bar, text="AI đọc mẫu cuốn sách và viết bản chỉ dẫn: văn phong, giọng kể, xưng hô giữa các nhân vật, "
                            "bảng thuật ngữ. Hãy đọc và sửa lại trước khi dịch – mọi lượt dịch đều tuân theo nó.",
                  foreground="#555", wraplength=760).pack(side="left", padx=10)

        pw = ttk.PanedWindow(self, orient="horizontal")
        pw.pack(fill="both", expand=True, pady=(10, 0))
        left = ttk.Frame(pw)
        pw.add(left, weight=3)
        ttk.Label(left, text="Hồ sơ tác phẩm (chỉ dẫn cho người dịch & biên tập)", style="Title.TLabel").pack(anchor="w")
        self.brief = _scrolled_text(left, height=20)
        self.brief.frame.pack(fill="both", expand=True, pady=4)
        ttk.Label(left, text="Chỉ dẫn riêng của bạn (ưu tiên cao nhất) – ví dụ: “Giữ nguyên tên riêng; lời thoại dùng gạch "
                             "đầu dòng; người kể xưng ‘tôi’; tránh từ Hán Việt”", foreground="#555").pack(anchor="w")
        self.custom = _scrolled_text(left, height=5)
        self.custom.frame.pack(fill="x", pady=4)

        right = ttk.Frame(pw)
        pw.add(right, weight=2)
        ttk.Label(right, text="Bảng thuật ngữ & tên riêng", style="Title.TLabel").pack(anchor="w")
        self.tv = _tree(right, [("en", "Tiếng Anh", 150), ("vi", "Tiếng Việt", 150), ("note", "Ghi chú", 160),
                                ("src", "Nguồn", 60)], selectmode="browse")
        self.tv.frame.pack(fill="both", expand=True, pady=4)
        self.tv.bind("<<TreeviewSelect>>", lambda e: self._pick())
        form = ttk.Frame(right)
        form.pack(fill="x")
        self.g_en, self.g_vi, self.g_note = tk.StringVar(), tk.StringVar(), tk.StringVar()
        for i, (lbl, var) in enumerate((("EN", self.g_en), ("VI", self.g_vi), ("Ghi chú", self.g_note))):
            ttk.Label(form, text=lbl).grid(row=i, column=0, sticky="w")
            ttk.Entry(form, textvariable=var).grid(row=i, column=1, sticky="ew", pady=2)
        form.columnconfigure(1, weight=1)
        gb = ttk.Frame(right)
        gb.pack(fill="x", pady=4)
        ttk.Button(gb, text="Thêm / cập nhật", command=self._upsert).pack(side="left")
        ttk.Button(gb, text="Xoá", command=self._delete).pack(side="left", padx=6)
        ttk.Label(right, text="Nguồn: ai = từ bước phân tích · auto = model tự đề xuất khi dịch · user = bạn nhập",
                  foreground="#777", wraplength=420).pack(anchor="w")

    def load(self):
        p = self.app.project
        self.brief.delete("1.0", "end")
        self.custom.delete("1.0", "end")
        if p:
            self.brief.insert("1.0", p.get_meta("brief"))
            self.custom.insert("1.0", p.get_meta("custom_instructions"))
        self.brief.edit_modified(False)
        self.custom.edit_modified(False)
        self.refresh_glossary()

    def save(self):
        p = self.app.project
        if not p:
            return
        if self.brief.edit_modified():
            p.set_meta("brief", self.brief.get("1.0", "end").strip())
            self.brief.edit_modified(False)
        if self.custom.edit_modified():
            p.set_meta("custom_instructions", self.custom.get("1.0", "end").strip())
            self.custom.edit_modified(False)

    def refresh_glossary(self):
        self.tv.delete(*self.tv.get_children())
        if self.app.project:
            for g in self.app.project.glossary():
                self.tv.insert("", "end", iid=g["en"], values=(g["en"], g["vi"], g["note"] or "", g["source"] or ""))

    def _pick(self):
        sel = self.tv.selection()
        if sel:
            en, vi, note, _ = self.tv.item(sel[0], "values")
            self.g_en.set(en)
            self.g_vi.set(vi)
            self.g_note.set(note)

    def _upsert(self):
        if self.app.require_project() and self.app.project.upsert_term(self.g_en.get(), self.g_vi.get(), self.g_note.get(), "user"):
            self.refresh_glossary()

    def _delete(self):
        sel = self.tv.selection()
        if sel and self.app.project:
            self.app.project.delete_term(sel[0])
            self.refresh_glossary()

    def analyze(self):
        if not self.app.require_project():
            return
        self.save()
        if self.app.project.get_meta("brief") and not messagebox.askyesno(
                APP_NAME, "Hồ sơ đã có nội dung. Phân tích lại sẽ thay thế phần hồ sơ (bảng thuật ngữ chỉ được bổ sung). Tiếp tục?"):
            return

        def done(res):
            brief, glossary = res
            self.brief.delete("1.0", "end")
            self.brief.insert("1.0", brief)
            self.brief.edit_modified(True)
            self.save()
            for en, vi, note in glossary:
                self.app.project.upsert_term(en, vi, note, "ai", overwrite=False)
            self.refresh_glossary()
            messagebox.showinfo(APP_NAME, "Đã có hồ sơ tác phẩm. Hãy đọc kỹ, chỉnh xưng hô / tên riêng nếu cần, "
                                          "rồi sang tab 3 để dịch.")

        self.app.run_task("Phân tích tác phẩm", lambda pipe: pipe.analyze(), done)


# ======================================================================== translate


class TranslateTab(ttk.Frame):
    def __init__(self, master, app: App):
        super().__init__(master, padding=10)
        self.app = app
        self.current_href: str | None = None
        self.review_var = tk.BooleanVar(value=app.settings.review_pass)

        bar = ttk.Frame(self)
        bar.pack(fill="x")
        self.btn_doc = ttk.Button(bar, text="▶ Dịch chương đã chọn", style="Accent.TButton", command=self.translate_selected)
        self.btn_doc.pack(side="left")
        self.btn_all = ttk.Button(bar, text="▶▶ Dịch toàn bộ sách", command=self.translate_all)
        self.btn_all.pack(side="left", padx=6)
        self.btn_stop = ttk.Button(bar, text="■ Dừng", command=app.stop, state="disabled")
        self.btn_stop.pack(side="left")
        ttk.Checkbutton(bar, text="Biên tập lượt 2 (chất lượng cao hơn, tốn gấp đôi quota)", variable=self.review_var,
                        command=self._review_changed).pack(side="left", padx=14)
        self.estimate = ttk.Label(bar, text="", foreground="#555")
        self.estimate.pack(side="right")

        pw = ttk.PanedWindow(self, orient="horizontal")
        pw.pack(fill="both", expand=True, pady=8)
        self.docs = _tree(pw, [("title", "Chương / phần", 230), ("prog", "Tiến độ", 80)], selectmode="extended")
        pw.add(self.docs.frame, weight=1)
        self.docs.bind("<<TreeviewSelect>>", lambda e: self._doc_selected())

        right = ttk.PanedWindow(pw, orient="vertical")
        pw.add(right, weight=4)
        self.units = _tree(right, [("n", "#", 44), ("en", "Tiếng Anh", 420), ("vi", "Tiếng Việt", 420),
                                   ("st", "Trạng thái", 100)], selectmode="extended")
        for st, color in STATUS_COLORS.items():
            self.units.tag_configure(st, foreground=color)
        right.add(self.units.frame, weight=3)
        self.units.bind("<<TreeviewSelect>>", lambda e: self._unit_selected())

        editor = ttk.Frame(right)
        right.add(editor, weight=2)
        panes = ttk.Frame(editor)
        panes.pack(fill="both", expand=True)
        panes.columnconfigure(0, weight=1)
        panes.columnconfigure(1, weight=1)
        panes.rowconfigure(1, weight=1)
        ttk.Label(panes, text="Bản gốc").grid(row=0, column=0, sticky="w")
        self.info = ttk.Label(panes, text="Bản dịch (sửa trực tiếp được; giữ nguyên các thẻ như <i1>…</i1>)", foreground="#555")
        self.info.grid(row=0, column=1, sticky="w")
        self.en_text = _scrolled_text(panes, height=7, background="#f6f4ef")
        self.en_text.frame.grid(row=1, column=0, sticky="nsew", padx=(0, 4))
        self.vi_text = _scrolled_text(panes, height=7)
        self.vi_text.frame.grid(row=1, column=1, sticky="nsew")
        tools = ttk.Frame(editor)
        tools.pack(fill="x", pady=(6, 0))
        ttk.Button(tools, text="💾 Lưu bản sửa", command=self.save_edit).pack(side="left")
        ttk.Label(tools, text="  Yêu cầu khi dịch lại:").pack(side="left")
        self.note = tk.StringVar()
        ttk.Entry(tools, textvariable=self.note, width=50).pack(side="left", padx=4)
        ttk.Button(tools, text="↻ Dịch lại các đoạn đã chọn", command=self.retranslate).pack(side="left")
        ttk.Button(tools, text="✎ Biên tập lại", command=self.review_selected).pack(side="left", padx=4)
        ttk.Button(tools, text="Đặt về chưa dịch", command=self.reset_selected).pack(side="left")

        self.log_text = tk.Text(self, height=6, font=("Consolas", 9), background="#fbfbfb", relief="flat", state="disabled")
        self.log_text.pack(fill="x")

    # ------------------------------------------------------------------ helpers
    def _review_changed(self):
        self.app.settings.review_pass = self.review_var.get()
        self.update_estimate()

    def set_running(self, running: bool):
        for b in (self.btn_doc, self.btn_all):
            b.config(state="disabled" if running else "normal")
        self.btn_stop.config(state="normal" if running else "disabled")

    def log(self, msg: str):
        self.log_text.config(state="normal")
        self.log_text.insert("end", msg + "\n")
        self.log_text.see("end")
        self.log_text.config(state="disabled")

    def load(self):
        self.refresh_docs(reset=True)
        self.update_estimate()

    def refresh_docs(self, reset: bool = False):
        p = self.app.project
        if not p:
            return
        sel = self.docs.selection()
        if reset:
            self.docs.delete(*self.docs.get_children())
        for d in p.docs():
            prog = f"{d['done'] or 0}/{d['total']}" + (f" ⚠{d['errors']}" if d["errors"] else "")
            vals = (d["title"], prog)
            if self.docs.exists(d["href"]):
                self.docs.item(d["href"], values=vals)
            else:
                self.docs.insert("", "end", iid=d["href"], values=vals)
        if reset and self.docs.get_children():
            first = next((h for h in self.docs.get_children() if not h.startswith("__")), self.docs.get_children()[0])
            self.docs.selection_set(first)
            self.docs.see(first)
        elif sel:
            pass
        self.update_estimate()

    def update_estimate(self):
        p = self.app.project
        if not p:
            return
        remaining = p.units(statuses=("pending", "error"))
        chars = sum(len(u["src"]) for u in remaining)
        s = self.app.settings
        reqs = math.ceil(chars / max(s.chunk_chars, 500)) if chars else 0
        if self.review_var.get():
            reqs *= 2
        rpm = next((c.rpm for c in s.translators()), 0)
        mins = f", ≈ {reqs / rpm:.0f} phút ở {rpm} yêu cầu/phút" if rpm and reqs else ""
        self.estimate.config(text=f"Còn {len(remaining):,} đoạn · ≈ {reqs:,} yêu cầu{mins}")

    def _doc_selected(self):
        sel = self.docs.selection()
        if not sel or not self.app.project:
            return
        self.current_href = sel[0]
        self.units.delete(*self.units.get_children())
        for u in self.app.project.units(self.current_href):
            self.units.insert("", "end", iid=u["uid"], values=self._row(u), tags=(u["status"],))

    @staticmethod
    def _row(u: dict):
        vi = Project.text_of(u)
        return (u["n"] + 1, plain_text(u["src"])[:200], plain_text(vi)[:200] if vi else "",
                STATUS_LABEL.get(u["status"], u["status"]))

    def refresh_unit(self, uid: str):
        if not self.units.exists(uid):
            return
        u = self.app.project.unit(uid)
        if u:
            self.units.item(uid, values=self._row(u), tags=(u["status"],))
            sel = self.units.selection()
            if len(sel) == 1 and sel[0] == uid:
                self._unit_selected()

    def _unit_selected(self):
        sel = self.units.selection()
        if len(sel) != 1:
            return
        u = self.app.project.unit(sel[0])
        if not u:
            return
        for t, val in ((self.en_text, u["src"]), (self.vi_text, Project.text_of(u))):
            t.config(state="normal")
            t.delete("1.0", "end")
            t.insert("1.0", val or "")
        self.en_text.config(state="disabled")
        extra = f" · {u['model']}" if u.get("model") else ""
        if u.get("note"):
            extra += f" · {u['note']}"
        self.info.config(text=f"Bản dịch – {STATUS_LABEL.get(u['status'], u['status'])}{extra}")

    def _selected_uids(self) -> list[str]:
        return list(self.units.selection())

    # ------------------------------------------------------------------ actions
    def translate_selected(self):
        if not self.app.require_project():
            return
        hrefs = list(self.docs.selection())
        if not hrefs:
            messagebox.showinfo(APP_NAME, "Chọn một hoặc nhiều chương ở cột bên trái.")
            return
        review = self.review_var.get()
        self.app.run_task("Dịch", lambda pipe: pipe.translate_docs(hrefs, review))

    def translate_all(self):
        if not self.app.require_project():
            return
        if not self.app.project.get_meta("brief") and not messagebox.askyesno(
                APP_NAME, "Chưa có hồ sơ tác phẩm – bản dịch sẽ kém nhất quán về giọng văn và xưng hô.\n"
                          "Vẫn dịch luôn? (Khuyên dùng: tab 2 → Phân tích tác phẩm)"):
            return
        hrefs = [d["href"] for d in self.app.project.docs()]
        review = self.review_var.get()
        self.app.run_task("Dịch toàn bộ sách", lambda pipe: pipe.translate_docs(hrefs, review))

    def save_edit(self):
        sel = self._selected_uids()
        if len(sel) != 1:
            messagebox.showinfo(APP_NAME, "Chọn đúng một đoạn để lưu bản sửa.")
            return
        u = self.app.project.unit(sel[0])
        text = " ".join(self.vi_text.get("1.0", "end").split())
        lost = placeholders_in(u["src"]) - placeholders_in(text)
        if lost and not messagebox.askyesno(APP_NAME, f"Bản sửa thiếu thẻ định dạng {', '.join(sorted(lost))}"
                                                      " (in nghiêng, chú thích…). Vẫn lưu?"):
            return
        self.app.project.save_user_edit(sel[0], text)
        self.refresh_unit(sel[0])
        self.app.refresh_stats()

    def retranslate(self):
        uids = self._selected_uids()
        if not uids:
            messagebox.showinfo(APP_NAME, "Chọn các đoạn cần dịch lại (giữ Ctrl/Shift để chọn nhiều).")
            return
        note, review = self.note.get(), self.review_var.get()
        self.app.run_task("Dịch lại", lambda pipe: pipe.retranslate(uids, note, review))

    def review_selected(self):
        uids = self._selected_uids()
        if uids:
            self.app.run_task("Biên tập lại", lambda pipe: pipe.review_only(uids))

    def reset_selected(self):
        uids = self._selected_uids()
        if uids and messagebox.askyesno(APP_NAME, f"Xoá bản dịch của {len(uids)} đoạn đã chọn?"):
            self.app.project.reset_units(uids)
            for uid in uids:
                self.refresh_unit(uid)
            self.refresh_docs()
            self.app.refresh_stats()


# =========================================================================== export


class ExportTab(ttk.Frame):
    def __init__(self, master, app: App):
        super().__init__(master, padding=16)
        self.app = app
        self.mode = tk.StringVar(value="bilingual")
        self.color = tk.StringVar(value=app.settings.vi_color)
        self.out = tk.StringVar()
        ttk.Label(self, text="Kiểu sách xuất ra", style="Title.TLabel").pack(anchor="w")
        ttk.Radiobutton(self, text="Song ngữ – mỗi đoạn tiếng Anh, ngay sau là đoạn tiếng Việt", value="bilingual",
                        variable=self.mode, command=self._suggest).pack(anchor="w", pady=2)
        ttk.Radiobutton(self, text="Chỉ tiếng Việt – thay toàn bộ nội dung bằng bản dịch", value="vi",
                        variable=self.mode, command=self._suggest).pack(anchor="w", pady=2)
        crow = ttk.Frame(self)
        crow.pack(anchor="w", pady=(10, 4))
        ttk.Label(crow, text="Màu chữ tiếng Việt (bản song ngữ):").pack(side="left")
        self.swatch = tk.Label(crow, text="   Mẫu chữ tiếng Việt   ", font=TEXT_FONT)
        self.swatch.pack(side="left", padx=6)
        ttk.Button(crow, text="Chọn màu…", command=self._pick_color).pack(side="left")
        self._update_swatch()
        orow = ttk.Frame(self)
        orow.pack(fill="x", pady=(16, 4))
        ttk.Label(orow, text="Lưu thành:").pack(side="left")
        ttk.Entry(orow, textvariable=self.out).pack(side="left", fill="x", expand=True, padx=6)
        ttk.Button(orow, text="Chọn…", command=self._browse).pack(side="left")
        brow = ttk.Frame(self)
        brow.pack(anchor="w", pady=12)
        ttk.Button(brow, text="📚  Xuất EPUB", style="Accent.TButton", command=self.export).pack(side="left")
        ttk.Button(brow, text="Mở thư mục", command=self._open_folder).pack(side="left", padx=8)
        self.report = ttk.Label(self, text="", wraplength=900, justify="left")
        self.report.pack(anchor="w", pady=8)
        ttk.Label(self, text="Đoạn chưa dịch sẽ giữ nguyên tiếng Anh, nên bạn có thể xuất thử bất cứ lúc nào để đọc "
                             "kiểm tra trên máy đọc sách / điện thoại.", foreground="#666", wraplength=900).pack(anchor="w")

    def load(self):
        self._suggest()

    def _suggest(self):
        if self.app.book:
            p = Path(self.app.book.path)
            suffix = ".song-ngu.epub" if self.mode.get() == "bilingual" else ".tieng-viet.epub"
            self.out.set(str(p.with_name(p.stem + suffix)))

    def _update_swatch(self):
        self.swatch.config(foreground=self.color.get())

    def _pick_color(self):
        c = colorchooser.askcolor(self.color.get(), parent=self)[1]
        if c:
            self.color.set(c)
            self.app.settings.vi_color = c
            self._update_swatch()

    def _browse(self):
        f = filedialog.asksaveasfilename(defaultextension=".epub", filetypes=[("EPUB", "*.epub")],
                                         initialfile=Path(self.out.get()).name if self.out.get() else "")
        if f:
            self.out.set(f)

    def _open_folder(self):
        if self.out.get():
            folder = str(Path(self.out.get()).parent)
            if sys.platform == "win32":
                os.startfile(folder)  # noqa: S606
            else:
                subprocess.Popen(["xdg-open", folder])

    def export(self):
        if not self.app.require_project():
            return
        out = self.out.get().strip()
        if not out:
            return
        if Path(out).resolve() == Path(self.app.book.path).resolve():
            messagebox.showerror(APP_NAME, "Không được ghi đè lên file gốc.")
            return
        try:
            self.app.config(cursor="watch")
            self.update_idletasks()
            rep = export_book(self.app.book.path, out, self.app.project.translations(), self.mode.get(), self.color.get())
        except Exception as e:  # noqa: BLE001
            messagebox.showerror(APP_NAME, f"Xuất thất bại:\n{e}")
            traceback.print_exc()
            return
        finally:
            self.app.config(cursor="")
        msg = f"✓ Đã xuất: {out}\n{rep.translated:,} đoạn đã dịch, {rep.untranslated:,} đoạn giữ nguyên tiếng Anh."
        if rep.mismatched:
            msg += f"\n⚠ {rep.mismatched} đoạn không khớp bản gốc (file EPUB đã bị thay đổi?) nên giữ nguyên."
        self.report.config(text=msg)
        save_settings(self.app.settings)


def main():
    if sys.platform == "win32":
        try:
            import ctypes
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except Exception:  # noqa: BLE001
            pass
    app = App()
    app.mainloop()


if __name__ == "__main__":
    main()
