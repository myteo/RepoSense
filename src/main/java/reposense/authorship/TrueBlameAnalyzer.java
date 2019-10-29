package reposense.authorship;

import static reposense.system.CommandRunner.runCommand;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import reposense.git.GitBlame;
import reposense.model.Author;
import reposense.model.CommitHash;
import reposense.model.RepoConfiguration;

/**
 * Analyzes true blame of a line
 */
public class TrueBlameAnalyzer {

    private static final double SIMILARITY_THRESHOLD = 0.6;
    private static Map<String, List<Diff>> cachedCommits = new HashMap<>();

    public static Author analyzeTrueBlame(
            RepoConfiguration repoConfig, String commitHash, String fileName, String actualLine, Author defaultAuthor) {

        // Git show commmit
        List<Diff> diffs;
        if (cachedCommits.containsKey(commitHash)) {
            diffs = cachedCommits.get(commitHash);
        } else {
            String gitShowCommand = "git show %s";
            Path repoRootPath = Paths.get(repoConfig.getRepoRoot());
            String commandResults = runCommand(repoRootPath, String.format(gitShowCommand, commitHash));
            diffs = parseGitShowResults(commandResults);
            cachedCommits.put(commitHash, diffs);
        }

        for (Diff diff : diffs) {
            if (!diff.getPostImageFileName().equals(fileName)) {
                continue;
            }
            Hunk hunk = diff.findMatchingHunk(actualLine);
            if (hunk == null) {
                continue;
            }
            int lineNumber = hunk.findHighestSimilarity(actualLine, SIMILARITY_THRESHOLD);
            if (lineNumber == -1) {
                break;
            }
            // Git blame <- parent of commit hash, preimage filename, preimage line number
            String blameResults = GitBlame.blamePrior(repoConfig.getRepoRoot(),  diff.getPreImageFileName(), commitHash, lineNumber);
            String[] blameResultLines = blameResults.split("\n");

            // Extract commmit hash, author from git blame results
            String priorCommitHash = blameResultLines[0].substring(0, FileInfoAnalyzer.FULL_COMMIT_HASH_LENGTH);
            String authorName = blameResultLines[1].substring(FileInfoAnalyzer.AUTHOR_NAME_OFFSET);
            String authorEmail = blameResultLines[2]
                    .substring(FileInfoAnalyzer.AUTHOR_EMAIL_OFFSET).replaceAll("<|>", "");
            Author author = repoConfig.getAuthor(authorName, authorEmail);

            // check that commit is not inside ignore commit list and author is not ignoring file
            // If meets similarity threshold
            if (author.getIgnoreGlobMatcher().matches(Paths.get(diff.getPreImageFileName()))
                    || CommitHash.isInsideCommitList(commitHash, repoConfig.getIgnoreCommitList())) {
                author = Author.UNKNOWN_AUTHOR;
            }
            // recursive call <- commit hash, preimage filename, actual line, author
            return analyzeTrueBlame(repoConfig, priorCommitHash, diff.getPreImageFileName(),
                    hunk.getPreImageLine(lineNumber).substring(1), author);
        }
        return defaultAuthor;
    }

    private static List<Diff> parseGitShowResults(String rawResults) {
        // Find diffs that contain file name as post image and the hunk that contains line number
        List<Diff> diffs = new ArrayList<>();
        String[] rawDiffs = rawResults.split("\ndiff --git ");

        for (int i = 1; i < rawDiffs.length; i++) {
            diffs.add(new Diff(rawDiffs[i]));
        }
        return diffs;
    }
}

class Diff {
    private List<Hunk> hunks = new ArrayList<>();
    private String preImageFileName;
    private String postImageFileName;

    public Diff(String rawDiff) {
        String diffHeader = rawDiff.substring(0, rawDiff.indexOf("\n"));
        Pattern fileNames = Pattern.compile("^a/(?<preImageFileName>.+?) b/(?<postImageFileName>.+)$");
        Matcher matcher = fileNames.matcher(diffHeader);

        if (matcher.matches()) {
            preImageFileName = matcher.group("preImageFileName");
            postImageFileName = matcher.group("postImageFileName");
        }

        String[] diffLines = rawDiff.split("\n");
        int i = 1;

        while (i < diffLines.length) {
            if (diffLines[i].startsWith("@")) {
                // TODO: d2 might not be  present
                Pattern hunkPattern =
                        Pattern.compile("^@@ -(?<d1>\\d+),(?<d2>\\d+) \\+(?<d3>\\d+),(?<d4>\\d+).*$");
                matcher = hunkPattern.matcher(diffLines[i]);
                if (matcher.matches()) {
                    Hunk hunk = new Hunk(Integer.parseInt(matcher.group("d1")));
                    i++;
                    while (i < diffLines.length && !diffLines[i].startsWith("@@")) {
                        if (diffLines[i].startsWith("-")) {
                            hunk.addPreImageLines(diffLines[i]);
                        } else if (diffLines[i].startsWith("+")) {
                            hunk.addPostImageLines(diffLines[i]);
                        } else {
                            hunk.addPreImageLines(diffLines[i]);
                            hunk.addPostImageLines(diffLines[i]);
                        }
                        i++;
                    }
                    hunks.add(hunk);
                    continue;
                }
            }
            i++;
        }
    }

    public Hunk findMatchingHunk(String line) {
        for (Hunk hunk : hunks) {
            if (hunk.containsPostImageLine(line)) {
                return hunk;
            }
        }
        return null;
    }

    public String getPreImageFileName() {
        return preImageFileName;
    }

    public String getPostImageFileName() {
        return postImageFileName;
    }
}


class Hunk {
    private int d1;
    private List<String> preImageLines = new ArrayList<>();
    private List<String> postImageLines = new ArrayList<>();

    public Hunk(int d1) {
        this.d1 = d1;
    }

    public void addPreImageLines(String line) {
        preImageLines.add(line);
    }

    public void addPostImageLines(String line) {
        postImageLines.add(line);
    }

    public String getPreImageLine(int lineNumber) {
        return preImageLines.get(lineNumber - d1);
    }

    public boolean containsPostImageLine(String line) {
        for (String postImageLine : postImageLines) {
            if (postImageLine.equals(String.format("+%s", line))) {
                return true;
            }
        }
        return false;
    }

    public int findHighestSimilarity(String line, double similarityThreshold) {
        double highestSoFar = similarityThreshold;
        int highestLineNumber = -1;
        for (int i = 0; i < preImageLines.size(); i++) {
            if (preImageLines.get(i).startsWith("-")) {
                double distance = similarityScore(preImageLines.get(i), line);
                if (distance > highestSoFar) {
                    highestSoFar = distance;
                    highestLineNumber = d1 + i;
                }
            }
        }
        return highestLineNumber;
    }

    private double similarityScore(String s, String baseString) {
        double levenshteinDistance = getLevenshteinDistance(s, baseString);
        return 1 - (levenshteinDistance / s.length());
    }

    private static int getLevenshteinDistance(String s, String t) {
        int[][] dp = new int[s.length() + 1][t.length() + 1];
        for (int i = 0; i <= s.length(); i++) {
            dp[i][0] = i;
        }
        for (int i = 0; i <= t.length(); i++) {
            dp[0][i] = i;
        }
        for (int i = 1; i <= s.length(); i++) {
            for (int j = 1; j <= t.length(); j++) {
                if (s.charAt(i - 1) == t.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    dp[i][j] = Math.min(dp[i - 1][j], Math.min(dp[i][j - 1], dp[i - 1][j - 1])) + 1;
                }
            }
        }
        return dp[s.length()][t.length()];
    }
}