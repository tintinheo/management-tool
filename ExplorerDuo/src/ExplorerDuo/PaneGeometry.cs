namespace ExplorerDuo;

public readonly record struct Box(int Left, int Top, int Right, int Bottom)
{
    public int Width => Right - Left;
    public int Height => Bottom - Top;
    public static Box FromRectangle(Rectangle r) => new(r.Left, r.Top, r.Right, r.Bottom);
    public override string ToString() => $"[{Left},{Top} {Width}x{Height}]";
}

/// <summary>Pure layout math for a glued left/right window pair.</summary>
public static class PaneGeometry
{
    public const int MinPaneWidth = 320;

    public static (Box Left, Box Right) Split(Box area, double ratio)
    {
        ratio = Math.Clamp(double.IsFinite(ratio) ? ratio : 0.5, 0.15, 0.85);
        int mid = area.Left + (int)Math.Round(area.Width * ratio);
        return (area with { Right = mid }, area with { Left = mid });
    }

    public static double RatioOf(Box left, Box right)
    {
        int total = left.Width + right.Width;
        return total <= 0 ? 0.5 : (double)left.Width / total;
    }

    public static Box Union(Box a, Box b) => new(
        Math.Min(a.Left, b.Left), Math.Min(a.Top, b.Top),
        Math.Max(a.Right, b.Right), Math.Max(a.Bottom, b.Bottom));

    /// <summary>
    /// The leader window changed from <paramref name="oldLeader"/> to <paramref name="newLeader"/>.
    /// Returns where the follower must go so the two stay glued:
    /// a move drags the follower along, dragging the shared (inner) edge acts as a splitter,
    /// dragging the outer edge leaves the follower alone. Top/bottom always match the leader.
    /// </summary>
    public static Box Follow(Box oldLeader, Box newLeader, Box follower, bool leaderIsLeft)
    {
        int dxLeft = newLeader.Left - oldLeader.Left;
        int dxRight = newLeader.Right - oldLeader.Right;
        bool translated = dxLeft == dxRight;

        if (leaderIsLeft)
        {
            int left = newLeader.Right;
            int right = translated ? follower.Right + dxRight : follower.Right;
            right = Math.Max(right, left + MinPaneWidth);
            return new Box(left, newLeader.Top, right, newLeader.Bottom);
        }
        else
        {
            int right = newLeader.Left;
            int left = translated ? follower.Left + dxLeft : follower.Left;
            left = Math.Min(left, right - MinPaneWidth);
            return new Box(left, newLeader.Top, right, newLeader.Bottom);
        }
    }
}
