namespace ExplorerDuo;

public readonly record struct Hotkey(uint Modifiers, uint Key)
{
    public const uint Alt = 1, Control = 2, Shift = 4, Win = 8;

    /// <summary>Parses "Ctrl+Alt+D", "Win+Shift+F2", "Ctrl+Alt+1"... At least one modifier is required.</summary>
    public static bool TryParse(string? text, out Hotkey hotkey)
    {
        hotkey = default;
        if (string.IsNullOrWhiteSpace(text)) return false;

        uint mods = 0;
        Keys key = Keys.None;
        foreach (var raw in text.Split('+', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
        {
            switch (raw.ToLowerInvariant())
            {
                case "ctrl": case "control": mods |= Control; break;
                case "alt": mods |= Alt; break;
                case "shift": mods |= Shift; break;
                case "win": case "windows": mods |= Win; break;
                default:
                    if (key != Keys.None) return false;
                    var token = raw.Length == 1 && char.IsDigit(raw[0]) ? "D" + raw : raw;
                    if (int.TryParse(token, out _) || !Enum.TryParse(token, true, out key)) return false;
                    if (key is Keys.None or Keys.ControlKey or Keys.ShiftKey or Keys.Menu or Keys.LWin or Keys.RWin) return false;
                    break;
            }
        }
        if (key == Keys.None || mods == 0) return false;
        hotkey = new Hotkey(mods, (uint)key);
        return true;
    }
}

/// <summary>Hidden window that receives WM_HOTKEY for globally registered shortcuts.</summary>
internal sealed class HotkeyHost : NativeWindow, IDisposable
{
    readonly Dictionary<int, Action> _actions = new();
    int _nextId = 1;

    public HotkeyHost() => CreateHandle(new CreateParams());

    public bool Register(Hotkey hotkey, Action action)
    {
        int id = _nextId++;
        if (!Native.RegisterHotKey(Handle, id, hotkey.Modifiers | Native.MOD_NOREPEAT, hotkey.Key)) return false;
        _actions[id] = action;
        return true;
    }

    public void Clear()
    {
        foreach (var id in _actions.Keys) Native.UnregisterHotKey(Handle, id);
        _actions.Clear();
    }

    protected override void WndProc(ref Message m)
    {
        if (m.Msg == Native.WM_HOTKEY && _actions.TryGetValue((int)m.WParam, out var action))
        {
            action();
            return;
        }
        base.WndProc(ref m);
    }

    public void Dispose()
    {
        Clear();
        DestroyHandle();
    }
}
