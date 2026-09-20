package com.osrstelemetry.plugin.storage;

import java.io.File;
import java.nio.file.Path;
import net.runelite.client.util.Filepath;

/**
 * Test-only Filepath construction/bridging.
 *
 * Filepath.Unchecked is deliberately confined to this test helper.
 * Production code must obtain Filepath instances through RuneLite's
 * supported plugin-directory/Filepath APIs.
 */
public final class TestFilepaths
{
    private TestFilepaths()
    {
    }

    public static Filepath rooted(Path path)
    {
        return Filepath.Unchecked.getRooted(path.toAbsolutePath().normalize());
    }

    public static Path path(Filepath filepath)
    {
        return Filepath.Unchecked.getPath(filepath);
    }

    public static File file(Filepath filepath)
    {
        return Filepath.Unchecked.getFile(filepath);
    }

    /**
     * Wrap an ordinary test-fixture File while keeping its parent as the
     * Filepath root. This allows production code under test to access sibling
     * temp files without exposing anything outside the fixture directory.
     */
    public static Filepath fromFile(File file)
    {
        Path path = file.toPath().toAbsolutePath().normalize();
        Path parent = path.getParent();

        if (parent == null)
        {
            throw new IllegalArgumentException("Test file must have a parent: " + file);
        }

        return Filepath.Unchecked.getRooted(parent)
            .joinSegment(path.getFileName().toString());
    }
}
