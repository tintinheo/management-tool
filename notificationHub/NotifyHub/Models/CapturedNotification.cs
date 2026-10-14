using System;
using System.ComponentModel;

namespace NotifyHub.Models;

/// <summary>A notification captured from the Windows notification center.</summary>
public sealed class CapturedNotification : INotifyPropertyChanged
{
    private bool _isRead;

    /// <summary>Database row id.</summary>
    public long Id { get; set; }

    /// <summary>Windows' own notification id (unique only while it sits in the notification center).</summary>
    public uint NotificationId { get; init; }

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

    public event PropertyChangedEventHandler? PropertyChanged;
}
