using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using Microsoft.Data.Sqlite;
using NotifyHub.Models;

namespace NotifyHub.Services;

/// <summary>Keeps a local history in %LOCALAPPDATA%\NotifyHub\notifications.db.</summary>
public sealed class NotificationStore
{
    private readonly string _connectionString;

    public NotificationStore()
    {
        var dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NotifyHub");
        Directory.CreateDirectory(dir);
        _connectionString = $"Data Source={Path.Combine(dir, "notifications.db")}";
        Initialize();
    }

    private SqliteConnection Open()
    {
        var c = new SqliteConnection(_connectionString);
        c.Open();
        return c;
    }

    private void Initialize()
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = """
            PRAGMA journal_mode = WAL;
            CREATE TABLE IF NOT EXISTS notifications (
                Id             INTEGER PRIMARY KEY AUTOINCREMENT,
                NotificationId INTEGER NOT NULL,
                AppId          TEXT    NOT NULL,
                AppName        TEXT    NOT NULL,
                Title          TEXT    NOT NULL,
                Body           TEXT    NOT NULL,
                CreatedAt      TEXT    NOT NULL,
                IsRead         INTEGER NOT NULL DEFAULT 0,
                UNIQUE (AppId, NotificationId, CreatedAt)
            );
            CREATE INDEX IF NOT EXISTS ix_notifications_created ON notifications (CreatedAt DESC);
            """;
        cmd.ExecuteNonQuery();
    }

    /// <summary>Inserts the notification; returns false if it was already stored (e.g. after an app restart).</summary>
    public bool TryInsert(CapturedNotification n)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = """
            INSERT OR IGNORE INTO notifications (NotificationId, AppId, AppName, Title, Body, CreatedAt)
            VALUES ($nid, $appId, $appName, $title, $body, $created)
            RETURNING Id;
            """;
        cmd.Parameters.AddWithValue("$nid", (long)n.NotificationId);
        cmd.Parameters.AddWithValue("$appId", n.AppId);
        cmd.Parameters.AddWithValue("$appName", n.AppName);
        cmd.Parameters.AddWithValue("$title", n.Title);
        cmd.Parameters.AddWithValue("$body", n.Body);
        cmd.Parameters.AddWithValue("$created", ToDb(n.CreatedAt));

        var result = cmd.ExecuteScalar();
        if (result is null || result is DBNull) return false;
        n.Id = Convert.ToInt64(result, CultureInfo.InvariantCulture);
        return true;
    }

    public List<CapturedNotification> LoadRecent(int limit = 2000)
    {
        var list = new List<CapturedNotification>();
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = """
            SELECT Id, NotificationId, AppId, AppName, Title, Body, CreatedAt, IsRead
            FROM notifications ORDER BY CreatedAt DESC LIMIT $limit;
            """;
        cmd.Parameters.AddWithValue("$limit", limit);

        using var r = cmd.ExecuteReader();
        while (r.Read())
        {
            list.Add(new CapturedNotification
            {
                Id = r.GetInt64(0),
                NotificationId = (uint)r.GetInt64(1),
                AppId = r.GetString(2),
                AppName = r.GetString(3),
                Title = r.GetString(4),
                Body = r.GetString(5),
                CreatedAt = DateTimeOffset.Parse(r.GetString(6), CultureInfo.InvariantCulture),
                IsRead = r.GetInt64(7) == 1,
            });
        }
        return list;
    }

    public void MarkRead(long id) => Execute("UPDATE notifications SET IsRead = 1 WHERE Id = $id;", id);
    public void MarkAllRead() => Execute("UPDATE notifications SET IsRead = 1 WHERE IsRead = 0;");
    public void Delete(long id) => Execute("DELETE FROM notifications WHERE Id = $id;", id);

    private void Execute(string sql, long? id = null)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = sql;
        if (id is not null) cmd.Parameters.AddWithValue("$id", id.Value);
        cmd.ExecuteNonQuery();
    }

    // UTC ISO-8601 strings sort correctly as text.
    private static string ToDb(DateTimeOffset value) =>
        value.UtcDateTime.ToString("O", CultureInfo.InvariantCulture);
}
