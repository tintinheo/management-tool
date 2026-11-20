using ExplorerDuo;
using Xunit;

public class HighlighterTests
{
    static Highlighter Make(Dictionary<string, string>? paths = null) =>
        new(paths ?? new(), ColorRule.Defaults(), "#8A8A8A");

    [Fact]
    public void Rules_colour_by_extension_case_insensitive()
    {
        var h = Make();
        Assert.Equal(ColorTranslator.FromHtml("#107C10"), h.ColorFor(@"C:\x\Setup.EXE", false, false));
        Assert.Equal(ColorTranslator.FromHtml("#8764B8"), h.ColorFor(@"C:\x\a.7z", false, false));
        Assert.Null(h.ColorFor(@"C:\x\notes.txt", false, false));
    }

    [Fact]
    public void File_rules_do_not_colour_folders_named_like_files()
    {
        Assert.Null(Make().ColorFor(@"C:\x\photos.jpg", isFolder: true, isHidden: false));
    }

    [Fact]
    public void Explicit_highlight_beats_rules_and_ignores_trailing_slash()
    {
        var h = Make(new() { [@"C:\x\Setup.exe"] = "#D13438", [@"D:\Work"] = "#0063B1" });
        Assert.Equal(ColorTranslator.FromHtml("#D13438"), h.ColorFor(@"c:\X\setup.exe", false, false));
        Assert.Equal(ColorTranslator.FromHtml("#0063B1"), h.ColorFor(@"D:\Work\", true, false));
    }

    [Fact]
    public void Hidden_items_fall_back_to_grey()
    {
        Assert.Equal(ColorTranslator.FromHtml("#8A8A8A"), Make().ColorFor(@"C:\x\.git", true, true));
    }

    [Fact]
    public void Wildcards()
    {
        Assert.Matches(Highlighter.Wildcard("*.*"), "README");
        Assert.Matches(Highlighter.Wildcard("a?c.txt; *.md"), "abc.txt");
        Assert.Matches(Highlighter.Wildcard("a?c.txt; *.md"), "x.MD");
        Assert.DoesNotMatch(Highlighter.Wildcard("a?c.txt"), "abbc.txt");
    }

    [Fact]
    public void Bad_colours_and_empty_rules_are_ignored()
    {
        var h = new Highlighter(new Dictionary<string, string> { [@"C:\a"] = "not-a-colour" },
            new[] { new ColorRule { Patterns = "*.x", Color = "nope" }, new ColorRule { Patterns = "", Color = "#FF0000" } }, null);
        Assert.True(h.IsEmpty);
    }

    [Fact]
    public void ColorRef_is_BGR() => Assert.Equal(0x00332211u, Highlighter.ToColorRef(Color.FromArgb(0x11, 0x22, 0x33)));
}

public class RenamePlannerTests
{
    static readonly DateTime Date = new(2026, 11, 20);

    [Fact]
    public void Tokens_and_counter()
    {
        var o = new RenameOptions(NameTemplate: "[P]_[C]_[N]", CounterStart: 7, CounterDigits: 3);
        Assert.Equal("Trip_009_IMG1.jpg", RenamePlanner.NewName(o, "IMG1.jpg", false, 2, "Trip", Date));
    }

    [Fact]
    public void Extension_template_date_and_case()
    {
        var o = new RenameOptions(NameTemplate: "[D] [N]", ExtTemplate: "[E]", Case: CaseMode.Lower);
        Assert.Equal("2026-11-20 report.pdf", RenamePlanner.NewName(o, "Report.PDF", false, 0, "x", Date));
        Assert.Equal("My Holiday Pics", RenamePlanner.NewName(new RenameOptions(Case: CaseMode.Title), "my HOLIDAY pics", true, 0, "x", Date));
    }

    [Fact]
    public void Find_replace_respects_match_case()
    {
        var o = new RenameOptions(Find: "draft", Replace: "final");
        Assert.Equal("final-final.docx", RenamePlanner.NewName(o, "Draft-draft.docx", false, 0, "", Date));
        Assert.Equal("Draft-final.docx", RenamePlanner.NewName(o with { MatchCase = true }, "Draft-draft.docx", false, 0, "", Date));
    }

    [Fact]
    public void Plan_flags_duplicates_and_invalid_names_then_executes_swap()
    {
        var dir = Directory.CreateTempSubdirectory("explorerduo-rename-").FullName;
        try
        {
            var a = Path.Combine(dir, "a.txt");
            var b = Path.Combine(dir, "b.txt");
            File.WriteAllText(a, "A");
            File.WriteAllText(b, "B");

            var dup = RenamePlanner.Plan(new[] { a, b }, new RenameOptions(NameTemplate: "same"));
            Assert.All(dup, p => Assert.Equal("Duplicate name", p.Error));
            Assert.Equal("Invalid character", RenamePlanner.Plan(new[] { a }, new RenameOptions(NameTemplate: "x:y"))[0].Error);
            Assert.Equal("Already exists", RenamePlanner.Plan(new[] { a }, new RenameOptions(NameTemplate: "b"))[0].Error);

            // a→b and b→a at the same time (two-phase rename).
            var swap = new List<RenameItem> { new(a, b, null), new(b, a, null) };
            RenamePlanner.Execute(swap);
            Assert.Equal("B", File.ReadAllText(a));
            Assert.Equal("A", File.ReadAllText(b));

            var plan = RenamePlanner.Plan(new[] { a, b }, new RenameOptions(NameTemplate: "doc_[C]", CounterDigits: 2));
            RenamePlanner.Execute(plan);
            Assert.True(File.Exists(Path.Combine(dir, "doc_01.txt")));
            Assert.True(File.Exists(Path.Combine(dir, "doc_02.txt")));
            Assert.Empty(Directory.GetFiles(dir, "~mr*"));
        }
        finally
        {
            Directory.Delete(dir, true);
        }
    }
}

public class FileSearchTests
{
    [Fact]
    public void Finds_by_name_content_and_depth_and_can_be_cancelled()
    {
        var root = Directory.CreateTempSubdirectory("explorerduo-find-").FullName;
        try
        {
            Directory.CreateDirectory(Path.Combine(root, "sub", "deep"));
            File.WriteAllText(Path.Combine(root, "a.txt"), "hello world");
            File.WriteAllText(Path.Combine(root, "b.md"), "nothing here");
            File.WriteAllText(Path.Combine(root, "sub", "deep", "c.txt"), new string('x', 70_000) + "HELLO");
            File.WriteAllText(Path.Combine(root, "sub", "report.pdf"), "pdf");

            List<string> Find(string names, string? text, bool recursive)
            {
                var found = new List<string>();
                FileSearch.Run(root, names, text, recursive, f => found.Add(Path.GetFileName(f.Path)), CancellationToken.None);
                return found.OrderBy(n => n).ToList();
            }

            Assert.Equal(new[] { "a.txt", "c.txt" }, Find("*.txt", null, true));
            Assert.Equal(new[] { "a.txt" }, Find("*.txt", null, false));
            Assert.Equal(new[] { "a.txt", "c.txt" }, Find("*", "hello", true)); // case-insensitive, across a 64K chunk boundary
            Assert.Equal(new[] { "b.md", "report.pdf" }, Find("*.md;*.PDF", null, true));
            Assert.Contains("deep", Find("de*", null, true)); // folders match by name too

            using var cts = new CancellationTokenSource();
            cts.Cancel();
            Assert.ThrowsAny<OperationCanceledException>(() => FileSearch.Run(root, "*", null, true, _ => { }, cts.Token));
        }
        finally
        {
            Directory.Delete(root, true);
        }
    }
}

public class ArchiverTests
{
    [Fact]
    public void Pack_and_unpack_round_trip_including_empty_folders()
    {
        var root = Directory.CreateTempSubdirectory("explorerduo-zip-").FullName;
        try
        {
            var src = Path.Combine(root, "src");
            Directory.CreateDirectory(Path.Combine(src, "Album", "Empty"));
            File.WriteAllText(Path.Combine(src, "Album", "song.txt"), "la la");
            File.WriteAllText(Path.Combine(src, "notes.md"), "# hi");

            var zip = Path.Combine(root, "out.zip");
            Archiver.Pack(new[] { Path.Combine(src, "Album") + "\\", Path.Combine(src, "notes.md") }, zip);
            Assert.Throws<IOException>(() => Archiver.Pack(new[] { Path.Combine(src, "notes.md") }, zip));

            var dest = Path.Combine(root, "dest");
            Directory.CreateDirectory(dest);
            var first = Archiver.Unpack(zip, dest);
            var second = Archiver.Unpack(zip, dest);
            Assert.Equal(Path.Combine(dest, "out"), first);
            Assert.Equal(Path.Combine(dest, "out (2)"), second);
            Assert.Equal("la la", File.ReadAllText(Path.Combine(first, "Album", "song.txt")));
            Assert.Equal("# hi", File.ReadAllText(Path.Combine(first, "notes.md")));
            Assert.True(Directory.Exists(Path.Combine(first, "Album", "Empty")));
        }
        finally
        {
            Directory.Delete(root, true);
        }
    }
}
