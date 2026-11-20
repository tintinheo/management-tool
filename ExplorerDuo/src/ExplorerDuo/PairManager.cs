namespace ExplorerDuo;

internal sealed class PanePair
{
    public IntPtr Left;
    public IntPtr Right;
    public Box LeftBox;
    public Box RightBox;
    public long SettleUntil;

    public bool Contains(IntPtr h) => h == Left || h == Right;
    public IntPtr Other(IntPtr h) => h == Left ? Right : Left;
    public bool IsLeft(IntPtr h) => h == Left;
    public Box BoxOf(IntPtr h) => h == Left ? LeftBox : RightBox;
    public void SetBox(IntPtr h, Box b) { if (h == Left) LeftBox = b; else RightBox = b; }
    public Box Bounds => PaneGeometry.Union(LeftBox, RightBox);
    public double Ratio => PaneGeometry.RatioOf(LeftBox, RightBox);
}

/// <summary>
/// Glues pairs of Explorer windows together: move/resize/minimize/maximize/activate one and the
/// other follows, so two native Explorer windows behave like one dual-pane window.
/// </summary>
internal sealed class PairManager : IDisposable
{
    readonly List<PanePair> _pairs = new();
    readonly Native.WinEventProc _proc;          // kept alive for the native hooks
    readonly List<IntPtr> _hooks = new();
    readonly HashSet<uint> _hookedPids = new();
    readonly System.Windows.Forms.Timer _watchdog;
    IntPtr _dragging;

    public IntPtr LastExplorer { get; private set; }
    public bool ExpandSurvivorOnClose { get; set; } = true;

    public PairManager()
    {
        _proc = OnWinEvent;
        _hooks.Add(Native.SetWinEventHook(Native.EVENT_SYSTEM_FOREGROUND, Native.EVENT_SYSTEM_MINIMIZEEND,
            IntPtr.Zero, _proc, 0, 0, Native.WINEVENT_OUTOFCONTEXT | Native.WINEVENT_SKIPOWNPROCESS));

        var fg = Native.GetForegroundWindow();
        if (WindowFrame.IsExplorer(fg)) LastExplorer = fg;

        _watchdog = new System.Windows.Forms.Timer { Interval = 1000 };
        _watchdog.Tick += (_, _) => Prune();
        _watchdog.Start();
    }

    public PanePair? Find(IntPtr h) => h == IntPtr.Zero ? null : _pairs.FirstOrDefault(p => p.Contains(h));

    public PanePair Pair(IntPtr left, IntPtr right, Box area, double ratio)
    {
        Remove(Find(left));
        Remove(Find(right));
        foreach (var h in new[] { left, right })
            if (Native.IsZoomed(h) || Native.IsIconic(h)) Native.ShowWindow(h, Native.SW_RESTORE);

        var pair = new PanePair { Left = left, Right = right };
        var (l, r) = PaneGeometry.Split(area, ratio);
        Place(pair, l, r);
        _pairs.Add(pair);
        Log.Write($"paired {left} {pair.LeftBox} + {right} {pair.RightBox}");
        HookProcessOf(left);
        HookProcessOf(right);
        return pair;
    }

    public void Unpair(PanePair pair) => Remove(pair);

    /// <summary>Ends dual-pane mode: optionally closes the partner and lets <paramref name="keep"/> take the whole area.</summary>
    public void Dissolve(PanePair pair, IntPtr keep, bool closeOther)
    {
        var bounds = pair.Bounds;
        Remove(pair);
        Log.Write($"dissolve: keep {keep}, closeOther={closeOther}");
        if (closeOther) Native.PostMessage(pair.Other(keep), Native.WM_CLOSE, IntPtr.Zero, IntPtr.Zero);
        if (Native.IsWindow(keep) && !Native.IsIconic(keep)) WindowFrame.SetVisible(keep, bounds);
    }

    public void Swap(PanePair pair)
    {
        var l = pair.LeftBox;
        var r = pair.RightBox;
        var newLeft = new Box(l.Left, l.Top, l.Left + r.Width, l.Bottom);
        var newRight = new Box(newLeft.Right, l.Top, newLeft.Right + l.Width, l.Bottom);
        (pair.Left, pair.Right) = (pair.Right, pair.Left);
        Place(pair, newLeft, newRight);
    }

    void Relayout(PanePair pair, Box area)
    {
        var (l, r) = PaneGeometry.Split(area, pair.Ratio);
        Place(pair, l, r);
    }

    static void Place(PanePair pair, Box l, Box r)
    {
        WindowFrame.SetVisible(pair.Left, l);
        WindowFrame.SetVisible(pair.Right, r);
        pair.LeftBox = WindowFrame.GetVisible(pair.Left);
        pair.RightBox = WindowFrame.GetVisible(pair.Right);
        // Explorer sometimes repositions a freshly opened window on its own; snap back for a moment.
        pair.SettleUntil = Environment.TickCount64 + 900;
    }

    void Remove(PanePair? pair)
    {
        if (pair != null) _pairs.Remove(pair);
    }

    void HookProcessOf(IntPtr hwnd)
    {
        Native.GetWindowThreadProcessId(hwnd, out var pid);
        if (pid == 0 || !_hookedPids.Add(pid)) return;
        _hooks.Add(Native.SetWinEventHook(Native.EVENT_OBJECT_DESTROY, Native.EVENT_OBJECT_DESTROY,
            IntPtr.Zero, _proc, pid, 0, Native.WINEVENT_OUTOFCONTEXT));
        _hooks.Add(Native.SetWinEventHook(Native.EVENT_OBJECT_LOCATIONCHANGE, Native.EVENT_OBJECT_LOCATIONCHANGE,
            IntPtr.Zero, _proc, pid, 0, Native.WINEVENT_OUTOFCONTEXT));
    }

    void OnWinEvent(IntPtr hook, uint evt, IntPtr hwnd, int idObject, int idChild, uint thread, uint time)
    {
        if (hwnd == IntPtr.Zero) return;
        try
        {
            if (evt == Native.EVENT_SYSTEM_FOREGROUND)
            {
                if (WindowFrame.IsExplorer(hwnd)) LastExplorer = hwnd;
                var active = Find(hwnd);
                if (active != null) KeepPartnerAbove(active, hwnd);
                return;
            }

            if (idObject != Native.OBJID_WINDOW || idChild != Native.CHILDID_SELF) return;
            var pair = Find(hwnd);
            if (pair == null) return;
            var other = pair.Other(hwnd);

            switch (evt)
            {
                case Native.EVENT_SYSTEM_MOVESIZESTART:
                    _dragging = hwnd;
                    break;
                case Native.EVENT_SYSTEM_MOVESIZEEND:
                    _dragging = IntPtr.Zero;
                    Sync(pair, hwnd);
                    break;
                case Native.EVENT_SYSTEM_MINIMIZESTART:
                    if (!Native.IsIconic(other)) Native.ShowWindow(other, Native.SW_SHOWMINNOACTIVE);
                    break;
                case Native.EVENT_SYSTEM_MINIMIZEEND:
                    if (Native.IsIconic(other)) Native.ShowWindow(other, Native.SW_SHOWNOACTIVATE);
                    KeepPartnerAbove(pair, hwnd);
                    break;
                case Native.EVENT_OBJECT_DESTROY:
                    Closed(pair, hwnd);
                    break;
                case Native.EVENT_OBJECT_LOCATIONCHANGE:
                    if (_dragging != IntPtr.Zero && _dragging != hwnd) break;
                    Sync(pair, hwnd);
                    break;
            }
        }
        catch
        {
            // Never let a hook callback throw into the message loop.
        }
    }

    static void KeepPartnerAbove(PanePair pair, IntPtr active)
    {
        var other = pair.Other(active);
        if (Native.IsWindow(other) && !Native.IsIconic(other))
            Native.SetWindowPos(other, active, 0, 0, 0, 0,
                Native.SWP_NOMOVE | Native.SWP_NOSIZE | Native.SWP_NOACTIVATE | Native.SWP_NOOWNERZORDER);
    }

    void Sync(PanePair pair, IntPtr hwnd)
    {
        if (!Native.IsWindow(hwnd) || Native.IsIconic(hwnd)) return;
        var other = pair.Other(hwnd);
        if (!Native.IsWindow(other) || Native.IsIconic(other)) return;

        if (Native.IsZoomed(hwnd))
        {
            // "Maximize" one pane = the pair fills the monitor, split at the current ratio.
            Native.ShowWindow(hwnd, Native.SW_RESTORE);
            Relayout(pair, Box.FromRectangle(Screen.FromHandle(hwnd).WorkingArea));
            return;
        }

        var now = WindowFrame.GetVisible(hwnd);
        var last = pair.BoxOf(hwnd);
        if (now == last) return; // our own move echoing back, or nothing changed

        if (_dragging == IntPtr.Zero && Environment.TickCount64 < pair.SettleUntil)
        {
            WindowFrame.SetVisible(hwnd, last);
            return;
        }

        var follow = PaneGeometry.Follow(last, now, WindowFrame.GetVisible(other), pair.IsLeft(hwnd));
        pair.SetBox(hwnd, now);
        WindowFrame.SetVisible(other, follow);
        pair.SetBox(other, WindowFrame.GetVisible(other));
        Log.Write($"sync: {hwnd} {last} -> {now}; partner {other} -> {pair.BoxOf(other)} (wanted {follow})");
    }

    void Closed(PanePair pair, IntPtr gone)
    {
        if (!_pairs.Contains(pair)) return;
        Log.Write($"pane {gone} closed (IsWindow={Native.IsWindow(gone)}, visible={Native.IsWindowVisible(gone)}); survivor {pair.Other(gone)} expands to {pair.Bounds}");
        var bounds = pair.Bounds;
        _pairs.Remove(pair);
        var survivor = pair.Other(gone);
        if (ExpandSurvivorOnClose && Native.IsWindow(survivor) && Native.IsWindowVisible(survivor) && !Native.IsIconic(survivor))
            WindowFrame.SetVisible(survivor, bounds);
    }

    void Prune()
    {
        foreach (var pair in _pairs.ToList())
        {
            foreach (var h in new[] { pair.Left, pair.Right })
            {
                if (!Native.IsWindow(h) || !Native.IsWindowVisible(h))
                {
                    Closed(pair, h);
                    break;
                }
            }
        }
    }

    public void Dispose()
    {
        _watchdog.Dispose();
        foreach (var h in _hooks) if (h != IntPtr.Zero) Native.UnhookWinEvent(h);
        _hooks.Clear();
    }
}
