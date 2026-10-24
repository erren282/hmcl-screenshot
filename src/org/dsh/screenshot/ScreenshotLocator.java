/*
 * 截图管理插件 —— 截图定位
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 *
 * 显示思路参照 PCL-CE 的 PageInstanceScreenshot：
 *   screenshotPath = McInstance.PathIndie + "screenshots\"
 * 只用实例自己的目录，不与全局目录合并。
 */
package org.dsh.screenshot;

import org.jackhuang.hmcl.game.HMCLGameRepository;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/// Locates and lists an instance's screenshots.
///
/// <p>Follows PCL-CE's approach: a single directory per instance, derived from where that
/// instance actually runs. Nothing is merged from the shared `.minecraft/screenshots` folder
/// unless the instance itself runs there — merging unconditionally made isolated instances show
/// captures that belonged to other versions.</p>
final class ScreenshotLocator {

    /// Extensions PCL accepts. Minecraft writes PNG, but mods also emit the others.
    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of("png", "jpg", "jpeg", "bmp", "webp", "tiff", "tif");

    private ScreenshotLocator() {
    }

    /// Returns the directory that holds this instance's screenshots.
    ///
    /// <p>The directory is created when missing, matching PCL — an empty page that can still open
    /// its folder is friendlier than one that reports the folder does not exist.</p>
    ///
    /// @param repository the instance's repository
    /// @param version    the instance name
    /// @return the screenshot directory, or `null` when it cannot be resolved
    static Path screenshotDirectory(HMCLGameRepository repository, String version) {
        if (repository == null || version == null)
            return null;

        try {
            Path runDirectory = repository.getRunDirectory(version);
            if (runDirectory == null)
                return null;
            Path screenshots = runDirectory.resolve("screenshots");
            Files.createDirectories(screenshots);
            return screenshots;
        } catch (Throwable e) {
            return null;
        }
    }

    /// Lists the screenshots in one directory.
    ///
    /// <p>Empty and hidden files are skipped: a half-written capture cannot be decoded, and
    /// hidden files are not something the user put there to look at. Ordering is newest first, by
    /// creation time like PCL, falling back to modification time when creation time is absent.</p>
    ///
    /// @param directory the directory to scan, may be `null`
    /// @return the image files, newest first
    static List<Path> list(Path directory) {
        List<Path> files = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory))
            return files;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                if (!Files.isRegularFile(entry) || !hasImageExtension(entry))
                    continue;
                try {
                    if (Files.size(entry) == 0L)
                        continue;
                    if (Files.isHidden(entry))
                        continue;
                } catch (IOException ignored) {
                    continue;
                }
                files.add(entry);
            }
        } catch (IOException ignored) {
            // An unreadable directory simply contributes nothing.
        }

        files.sort(Comparator.comparing(ScreenshotLocator::creationTime).reversed());
        return files;
    }

    /// Returns a file's creation time, or its modification time when creation time is missing.
    ///
    /// @param path the file
    /// @return the timestamp
    private static FileTime creationTime(Path path) {
        try {
            Object created = Files.getAttribute(path, "creationTime");
            if (created instanceof FileTime time)
                return time;
        } catch (Throwable ignored) {
            // Fall back to modification time.
        }
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    /// Returns whether a path looks like an image PCL would show.
    ///
    /// @param path the file
    /// @return whether it is an image
    private static boolean hasImageExtension(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && IMAGE_EXTENSIONS.contains(name.substring(dot + 1));
    }
}
