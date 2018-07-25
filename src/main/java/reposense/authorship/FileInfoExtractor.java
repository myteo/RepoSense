import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
        logger.info("Extracting relevant file infos " + config.getLocation() + "...");
        final PathMatcher ignoreGlobMatcher = getIgnoreGlobMatcher(config.getIgnoreGlobList());

            if (shouldIgnore(filePath, ignoreGlobMatcher)) {
                continue;
            }

        final PathMatcher ignoreGlobMatcher = getIgnoreGlobMatcher(config.getIgnoreGlobList());
                if (shouldIgnore(relativePath, ignoreGlobMatcher)) {
                    continue;
     * Returns true if {@code ignoreGlobMatcher} matchers the file path at {@code name}.
    private static boolean shouldIgnore(String name, PathMatcher ignoreGlobMatcher) {
        return ignoreGlobMatcher.matches(Paths.get(name));

    /**
     * Returns a {@code PathMatcher} that matches any file paths which satisfy any one of the glob patterns in
     * {@code ignoreGlobList}.
     */
    private static PathMatcher getIgnoreGlobMatcher(List<String> ignoreGlobList) {
        String globString = "glob:{" + String.join(",", ignoreGlobList) + "}";
        return FileSystems.getDefault().getPathMatcher(globString);
    }