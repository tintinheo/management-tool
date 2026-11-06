using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using NotifyHub.Models;
using Windows.UI.Notifications;
using Windows.UI.Notifications.Management;

namespace NotifyHub.Services;

/// <summary>
/// Reads toast notifications from the Windows notification center.
/// Desktop apps can't rely on UserNotificationListener.NotificationChanged, so we poll
/// and diff by notification id instead.
/// </summary>
public sealed class NotificationCaptureService : IDisposable
{
    private readonly UserNotificationListener _listener = UserNotificationListener.Current;
    private readonly HashSet<uint> _seen = new();
    private volatile Dictionary<uint, string> _live = new();
    private CancellationTokenSource? _cts;

    /// <summary>Raised on a background thread for every notification not seen before.</summary>
    public event Action<CapturedNotification>? NotificationCaptured;

    /// <summary>Raised on a background thread when a poll fails.</summary>
    public event Action<Exception>? PollFailed;

    /// <summary>Must be called from the UI thread; shows the Windows consent prompt the first time.</summary>
    public async Task<UserNotificationListenerAccessStatus> RequestAccessAsync()
        => await _listener.RequestAccessAsync();

    /// <summary>True while the notification is still in the Windows notification center.</summary>
    public bool IsInNotificationCenter(CapturedNotification n) =>
        _live.TryGetValue(n.NotificationId, out var appId) && appId == n.AppId;

    public void Start(TimeSpan interval)
    {
        Stop();
        _cts = new CancellationTokenSource();
        var token = _cts.Token;
        _ = Task.Run(() => LoopAsync(interval, token), token);
    }

    public void Stop()
    {
        _cts?.Cancel();
        _cts?.Dispose();
        _cts = null;
    }

    private async Task LoopAsync(TimeSpan interval, CancellationToken token)
    {
        using var timer = new PeriodicTimer(interval);
        try
        {
            do
            {
                try { await PollAsync(); }
                catch (Exception ex) { PollFailed?.Invoke(ex); }
            }
            while (await timer.WaitForNextTickAsync(token));
        }
        catch (OperationCanceledException) { /* stopping */ }
    }

    private async Task PollAsync()
    {
        var current = await _listener.GetNotificationsAsync(NotificationKinds.Toast);
        var currentIds = new HashSet<uint>();
        var live = new Dictionary<uint, string>();

        foreach (var n in current)
        {
            currentIds.Add(n.Id);
            live[n.Id] = TryGetAppId(n);
            if (!_seen.Add(n.Id)) continue;

            var captured = Map(n);
            if (captured is not null) NotificationCaptured?.Invoke(captured);
        }

        // Forget ids that left the notification center so the set doesn't grow forever.
        _seen.IntersectWith(currentIds);
        _live = live;
    }

    private static string TryGetAppId(UserNotification n)
    {
        try { return n.AppInfo.AppUserModelId; }
        catch { return ""; }
    }

    private static CapturedNotification? Map(UserNotification n)
    {
        try
        {
            // Collect text from every template the app used (modern ToastGeneric and legacy ones),
            // so the full content is kept, not just the first lines.
            var texts = (n.Notification?.Visual?.Bindings ?? (IEnumerable<NotificationBinding>)Array.Empty<NotificationBinding>())
                .SelectMany(b => b.GetTextElements())
                .Select(t => t.Text?.Trim())
                .Where(t => !string.IsNullOrWhiteSpace(t))
                .Select(t => t!)
                .Distinct()
                .ToList();

            string appName = "Unknown app", appId = "";
            try
            {
                appName = n.AppInfo.DisplayInfo.DisplayName;
                appId = n.AppInfo.AppUserModelId;
            }
            catch { /* some senders don't expose app info */ }

            return new CapturedNotification
            {
                NotificationId = n.Id,
                AppId = appId,
                AppName = appName,
                Title = texts.FirstOrDefault() ?? "(no title)",
                Body = string.Join(Environment.NewLine, texts.Skip(1)),
                CreatedAt = n.CreationTime,
            };
        }
        catch
        {
            return null;
        }
    }

    public void Dispose() => Stop();
}
