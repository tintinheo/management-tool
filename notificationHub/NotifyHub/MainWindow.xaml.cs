using System;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Data;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using NotifyHub.Models;
using NotifyHub.Services;
using Windows.UI.Notifications.Management;

namespace NotifyHub;

public partial class MainWindow : Window
{
    private const string AllApps = "All apps";

    private readonly NotificationStore _store = new();
    private readonly NotificationCaptureService _capture = new();
    private readonly ObservableCollection<CapturedNotification> _items = new();
    private readonly ObservableCollection<string> _apps = new() { AllApps };
    private ICollectionView? _view;

    public MainWindow()
    {
        InitializeComponent();
        Loaded += OnLoaded;
        Closed += (_, _) => _capture.Dispose();
    }

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        _view = CollectionViewSource.GetDefaultView(_items);
        _view.Filter = Matches;
        NotificationList.ItemsSource = _view;
        AppFilter.ItemsSource = _apps;
        AppFilter.SelectedIndex = 0;

        foreach (var n in _store.LoadRecent()) Add(n, atTop: false);
        UpdateStatus();

        UserNotificationListenerAccessStatus status;
        try
        {
            status = await _capture.RequestAccessAsync();
        }
        catch (Exception ex)
        {
            StatusText.Text = "Couldn't connect to Windows notifications. Run NotifyHub from its packaging project " +
                              $"with the userNotificationListener capability. ({ex.Message})";
            return;
        }

        if (status != UserNotificationListenerAccessStatus.Allowed)
        {
            StatusText.Text = "NotifyHub doesn't have notification access. Turn it on in Settings > " +
                              "Privacy & security > Notifications, then restart NotifyHub.";
            return;
        }

        _capture.NotificationCaptured += OnCaptured;
        _capture.PollFailed += ex => Dispatcher.BeginInvoke(() => StatusText.Text = $"Reading notifications failed: {ex.Message}");
        _capture.Start(TimeSpan.FromSeconds(2));
    }

    // Background thread.
    private void OnCaptured(CapturedNotification n)
    {
        if (!_store.TryInsert(n)) return;
        Dispatcher.BeginInvoke(() =>
        {
            Add(n, atTop: true);
            UpdateStatus();
            TaskbarAttention.Flash(this);
        });
    }

    private void Add(CapturedNotification n, bool atTop)
    {
        if (atTop) _items.Insert(0, n);
        else _items.Add(n);

        if (!_apps.Contains(n.AppName)) _apps.Add(n.AppName);
    }

    private bool Matches(object o)
    {
        if (o is not CapturedNotification n) return false;

        if (AppFilter.SelectedItem is string app && app != AllApps && n.AppName != app)
            return false;

        var q = SearchBox.Text.Trim();
        return q.Length == 0
            || n.Title.Contains(q, StringComparison.OrdinalIgnoreCase)
            || n.Body.Contains(q, StringComparison.OrdinalIgnoreCase)
            || n.AppName.Contains(q, StringComparison.OrdinalIgnoreCase);
    }

    private void UpdateStatus()
    {
        var unread = _items.Count(i => !i.IsRead);
        StatusText.Text = $"{_items.Count} notifications, {unread} unread";
        EmptyText.Visibility = _items.Count == 0 ? Visibility.Visible : Visibility.Collapsed;

        // Unread total on the taskbar button and in the window title (shown in Alt+Tab and taskbar previews).
        TaskbarAttention.SetUnreadBadge(this, unread);
        Title = unread > 0 ? $"NotifyHub ({unread})" : "NotifyHub";
    }

    private void SearchBox_TextChanged(object sender, TextChangedEventArgs e) => _view?.Refresh();

    private void AppFilter_SelectionChanged(object sender, SelectionChangedEventArgs e) => _view?.Refresh();

    private void NotificationList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (NotificationList.SelectedItem is CapturedNotification { IsRead: false } n)
        {
            n.IsRead = true;
            _store.MarkRead(n.Id);
            UpdateStatus();
        }
    }

    private void MarkAllRead_Click(object sender, RoutedEventArgs e)
    {
        _store.MarkAllRead();
        foreach (var n in _items) n.IsRead = true;
        UpdateStatus();
    }

    // ---- Open the source app / act on the notification ----

    private void OpenApp_Click(object sender, RoutedEventArgs e) => OpenApp(ItemFrom(sender));

    private void NotificationList_MouseDoubleClick(object sender, MouseButtonEventArgs e)
    {
        // Ignore double-clicks on the buttons and links inside the card.
        if (IsInside<ButtonBase>(e.OriginalSource as DependencyObject)) return;
        OpenApp(NotificationList.SelectedItem as CapturedNotification);
    }

    private void NotificationList_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;
        OpenApp(NotificationList.SelectedItem as CapturedNotification);
        e.Handled = true;
    }

    private void OpenNotificationCenter_Click(object sender, RoutedEventArgs e)
    {
        var n = ItemFrom(sender);
        if (n is null) return;

        if (!_capture.IsInNotificationCenter(n))
        {
            StatusText.Text = "This notification has been cleared from Windows, so its reply box and buttons are gone. " +
                              "Use Open app instead.";
            return;
        }

        Try(AppLauncher.OpenNotificationCenter, "Couldn't open the notification center. Press Win+N instead.");
    }

    private void Link_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Hyperlink { DataContext: string url })
            Try(() => AppLauncher.OpenLink(url), "Couldn't open the link.");
    }

    private void OpenApp(CapturedNotification? n)
    {
        if (n is null) return;
        Try(() => AppLauncher.OpenApp(n.AppId), $"Couldn't open {n.AppName}.");
    }

    private void Try(Action action, string failureMessage)
    {
        try { action(); }
        catch (Exception ex) { StatusText.Text = $"{failureMessage} ({ex.Message})"; }
    }

    /// <summary>Card buttons carry the notification as DataContext; menu items fall back to the selection.</summary>
    private CapturedNotification? ItemFrom(object sender) =>
        (sender as FrameworkElement)?.DataContext as CapturedNotification
        ?? NotificationList.SelectedItem as CapturedNotification;

    private static bool IsInside<T>(DependencyObject? element) where T : DependencyObject
    {
        while (element is not null)
        {
            if (element is T) return true;
            element = element is Visual ? VisualTreeHelper.GetParent(element) : LogicalTreeHelper.GetParent(element);
        }
        return false;
    }

    // ---- Copy / delete ----

    private void Copy_Click(object sender, RoutedEventArgs e)
    {
        if (NotificationList.SelectedItem is CapturedNotification n)
            Clipboard.SetText($"{n.AppName}\n{n.Title}\n{n.Body}".TrimEnd());
    }

    private void Delete_Click(object sender, RoutedEventArgs e)
    {
        if (NotificationList.SelectedItem is CapturedNotification n)
        {
            _store.Delete(n.Id);
            _items.Remove(n);
            UpdateStatus();
        }
    }
}
