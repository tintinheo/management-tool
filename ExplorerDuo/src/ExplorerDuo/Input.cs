using System.Diagnostics;
using System.Runtime.InteropServices;

namespace ExplorerDuo;

internal static class Wait
{
    /// <summary>Polls <paramref name="probe"/> until it returns a non-default value or the timeout elapses.</summary>
    public static async Task<T?> Until<T>(Func<T?> probe, int timeoutMs, int intervalMs = 60)
    {
        var sw = Stopwatch.StartNew();
        while (true)
        {
            T? value = default;
            try { value = probe(); } catch { /* window/tab not ready yet */ }
            if (value is not null && !EqualityComparer<T>.Default.Equals(value, default!)) return value;
            if (sw.ElapsedMilliseconds > timeoutMs) return default;
            await Task.Delay(intervalMs);
        }
    }
}

internal static class Input
{
    public const ushort VK_SHIFT = 0x10, VK_CONTROL = 0x11, VK_MENU = 0x12, VK_LWIN = 0x5B, VK_RWIN = 0x5C;
    static readonly int[] Modifiers = { VK_SHIFT, VK_CONTROL, VK_MENU, VK_LWIN, VK_RWIN };

    /// <summary>Hotkeys arrive while Ctrl/Alt are still held; wait so synthetic Ctrl+T isn't seen as Ctrl+Alt+T.</summary>
    public static async Task WaitForModifiersReleasedAsync(int timeoutMs = 2000)
    {
        var sw = Stopwatch.StartNew();
        while (sw.ElapsedMilliseconds < timeoutMs && Modifiers.Any(vk => Native.GetAsyncKeyState(vk) < 0))
            await Task.Delay(25);
    }

    public static void SendChord(ushort modifier, char key)
    {
        ushort vk = char.ToUpperInvariant(key);
        var inputs = new[] { Key(modifier, false), Key(vk, false), Key(vk, true), Key(modifier, true) };
        Native.SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<Native.INPUT>());
    }

    static Native.INPUT Key(ushort vk, bool up) => new()
    {
        type = 1, // INPUT_KEYBOARD
        U = new Native.InputUnion { ki = new Native.KEYBDINPUT { wVk = vk, dwFlags = up ? 2u : 0u } },
    };

    public static async Task<bool> ActivateAsync(IntPtr hwnd)
    {
        if (!Native.IsWindow(hwnd)) return false;
        if (Native.IsIconic(hwnd)) Native.ShowWindow(hwnd, Native.SW_RESTORE);
        if (Native.GetForegroundWindow() == hwnd) return true;

        Native.SetForegroundWindow(hwnd);
        if (Native.GetForegroundWindow() != hwnd)
        {
            var fg = Native.GetForegroundWindow();
            uint fgThread = Native.GetWindowThreadProcessId(fg, out _);
            uint me = Native.GetCurrentThreadId();
            Native.AttachThreadInput(me, fgThread, true);
            Native.BringWindowToTop(hwnd);
            Native.SetForegroundWindow(hwnd);
            Native.AttachThreadInput(me, fgThread, false);
        }
        return await Wait.Until(() => Native.GetForegroundWindow() == hwnd, 1000);
    }
}
