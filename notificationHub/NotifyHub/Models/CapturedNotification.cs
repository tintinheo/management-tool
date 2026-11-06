using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using System.Text.RegularExpressions;

namespace NotifyHub.Models;

/// <summary>A notification captured from the Windows notification center.</summary>
public sealed class CapturedNotification : INotifyPropertyChanged
{
    private static readonly Regex UrlPattern = new(@"https?://[^\s<>""']+", RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private bool _isRead;
    private IReadOnlyList<string>? _links;

    /// <summary>Database row id.</summary>
    public long Id { get; set; }

    /// <summary>Windows' own notification id (unique only while it sits in the notification center).</summary>
    public uint NotificationId { get; init; }

    /// <summary>AppUserModelId of the sending app; used to open it.</summary>
    public string AppId { get; init; } = "";
    public string AppName { get; init; } = "Unknown app";
    public string Title { get; init; } = "";
    public string Body { get; init; } = "";
    public DateTimeOffset CreatedAt { get; init; }

    public bool IsRead
    {
        get => _isRead;
        set
        {
            if (_isRead == value) return;
            _isRead = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(IsRead)));
        }
    }

    public string LocalTime =>
        CreatedAt.ToLocalTime().Date == DateTime.Today
            ? CreatedAt.ToLocalTime().ToString("HH:mm")
            : CreatedAt.ToLocalTime().ToString("dd MMM, HH:mm");

    public bool HasBody => !string.IsNullOrWhiteSpace(Body);

    public bool CanOpenApp => !string.IsNullOrWhiteSpace(AppId);

    /// <summary>Web links found in the notification text (http/https only).</summary>
    public IReadOnlyList<string> Links => _links ??= UrlPattern
        .Matches($"{Title}\n{Body}")
        .Select(m => m.Value.TrimEnd('.', ',', ';', ':', ')', ']', '!', '?'))
        .Distinct(StringComparer.OrdinalIgnoreCase)
        .ToList();

    public bool HasLinks => Links.Count > 0;

    public event PropertyChangedEventHandler? PropertyChanged;
}
