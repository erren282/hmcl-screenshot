/*
 * 截图管理插件 —— 截图定位
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 */
package org.dsh.screenshot;

import org.jackhuang.hmcl.game.HMCLGameRepository;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/// Finds the screenshots that belong to one instance.
///
/// <p>Minecraft writes captures to `screenshots/` under the game directory. Whether that is the
/// instance's own folder depends on the instance's isolation setting, so both the instance
/// directory and the shared `.minecraft` directory are checked; the instance folder is preferred
/// when it has any images, because that is the one the game actually writes to when isolation is
/// on.</p>
final class ScreenshotLocator {

    /// Image extensions Minecraft can produce.
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg");

    private ScreenshotLocator() {
    }

    /// Returns the directory that holds this instance's screenshots.
    ///
    /// <p>`getRunDirectory` already resolves where the game actually writes for this instance —
    /// it honours the instance's `runningDirectory` override and the isolation setting. Asking it
    /// is therefore more accurate than guessing from paths, and it avoids merging the shared
    /// `.minecraft/screenshots` folder into isolated instances, which used to make every version
    /// show the same captures.</p>
    ///
    /// <p>When the resolved directory is the shared one, it is used as-is: those captures really
    /// are this instance's, since that is where the game put them.</p>
    ///
    /// @param repository the instance's repository
    /// @param version    the instance name
    /// @return the screenshot directory, or an empty list when it cannot be resolved
    static List<Path> candidateDirectories(HMCLGameRepository repository, String version) {
        List<Path> candidates = new ArrayList<>();
        if (repository == null || version == null)
            return candidates;

        try {
            Path runDirectory = repository.getRunDirectory(version);
            if (runDirectory != null) {
                Path screenshots = runDirectory.resolve("screenshots");
                addIfAbsent(candidates, screenshots);
                return candidates;
            }
        } catch (Throwable ignored) {
            // Nothing to show.
        }

        return candidates;
    }

    /// Describes where an instance's screenshots were looked for, for diagnostics.
    ///
    /// @param repository the instance's repository
    /// @param version    the instance name
    /// @return a human-readable description
    static String describe(HMCLGameRepository repository, String version) {
        if (repository == null || version == null)
            return "（无实例）";
        try {
            Path runDirectory = repository.getRunDirectory(version);
            return runDirectory == null ? "（无法解析运行目录）" : runDirectory.toString();
        } catch (Throwable e) {
            return "（解析失败：" + e + "）";
        }
    }

    /// Collects image files from the given directories.
    ///
    /// @param directories the directories to scan
    /// @return the image files, newest first
    static List<Path> collect(List<Path> directories) {
        List<Path> files = new ArrayList<>();
        for (Path directory : directories) {
            if (directory == null || !Files.isDirectory(directory))
                continue;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path entry : stream) {
                    if (Files.isRegularFile(entry) && hasImageExtension(entry))
                        addIfAbsent(files, entry);
                }
            } catch (IOException ignored) {
                // A locked or unreadable directory simply contributes nothing.
            }
        }

        files.sort(Comparator.comparingLong(ScreenshotLocator::lastModified).reversed());
        return files;
    }

    /// Returns the directory shown by “打开目录”, preferring one that actually has images.
    ///
    /// @param directories the candidate directories
    /// @return the directory to open, or `null`
    static Path preferredDirectory(List<Path> directories) {
        for (Path directory : directories) {
            if (directory != null && Files.isDirectory(directory) && hasImages(directory))
                return directory;
        }
        for (Path directory : directories) {
            if (directory != null && Files.isDirectory(directory))
                return directory;
        }
        return directories.isEmpty() ? null : directories.get(0);
    }

    /// Returns whether a directory contains at least one image.
    ///
    /// @param directory the directory
    /// @return whether it holds an image
    private static boolean hasImages(Path directory) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry) && hasImageExtension(entry))
                    return true;
            }
        } catch (IOException ignored) {
            // Treated as empty.
        }
        return false;
    }

    /// Returns whether a path looks like an image Minecraft produced.
    ///
    /// @param path the file
    /// @return whether it is an image
    private static boolean hasImageExtension(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && IMAGE_EXTENSIONS.contains(name.substring(dot + 1));
    }

    /// Returns a file's modification time, or `0` when it cannot be read.
    ///
    /// @param path the file
    /// @return the timestamp in milliseconds
    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /// Adds a value only when it is not already present and not `null`.
    ///
    /// @param list  the target list
    /// @param value the candidate
    private static <T> void addIfAbsent(List<T> list, T value) {
        if (value != null && !list.contains(value))
            list.add(value);
    }
}
