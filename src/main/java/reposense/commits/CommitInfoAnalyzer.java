package reposense.commits;

import static reposense.system.CommandRunner.runCommand;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import reposense.commits.model.CommitInfo;
import reposense.commits.model.CommitResult;
import reposense.model.Author;
import reposense.model.CommitHash;
import reposense.model.RepoConfiguration;
import reposense.system.LogsManager;
import reposense.util.FileUtil;

/**
 * Analyzes commit information found in the git log.
 */
public class CommitInfoAnalyzer {
    public static final DateFormat GIT_STRICT_ISO_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

    private static final Logger logger = LogsManager.getLogger(CommitInfoAnalyzer.class);
    private static final String MESSAGE_START_ANALYZING_COMMIT_INFO = "Analyzing commits info for %s (%s)...";

    private static final String LOG_SPLITTER = "\\|\\n\\|";

    private static final int COMMIT_HASH_INDEX = 0;
    private static final int AUTHOR_INDEX = 1;
    private static final int EMAIL_INDEX = 2;
    private static final int DATE_INDEX = 3;
    private static final int MESSAGE_TITLE_INDEX = 4;
    private static final int MESSAGE_BODY_INDEX = 5;

    private static final Pattern INSERTION_PATTERN = Pattern.compile("([0-9]+) insertion");
    private static final Pattern DELETION_PATTERN = Pattern.compile("([0-9]+) deletion");

    private static final Pattern MESSAGEBODY_LEADING_PATTERN = Pattern.compile("^ {4}", Pattern.MULTILINE);

    /**
     * Analyzes each {@code CommitInfo} in {@code commitInfos} and returns a list of {@code CommitResult} that is not
     * specified to be ignored or the author is inside {@code config}.
     */
    public static List<CommitResult> analyzeCommits(
            String path, List<CommitInfo> commitInfos, RepoConfiguration config
    ) {
        logger.info(String.format(MESSAGE_START_ANALYZING_COMMIT_INFO, config.getLocation(), config.getBranch()));

        return commitInfos.stream()
                .map(commitInfo -> analyzeCommit(path, commitInfo, config))
                .filter(commitResult -> !commitResult.getAuthor().equals(Author.UNKNOWN_AUTHOR)
                        && !CommitHash.isInsideCommitList(commitResult.getHash(), config.getIgnoreCommitList()))
                .sorted(Comparator.comparing(CommitResult::getTime))
                .collect(Collectors.toList());
    }

    /**
     * Extracts the relevant data from {@code commitInfo} into a {@code CommitResult}.
     */
    public static CommitResult analyzeCommit(String path, CommitInfo commitInfo, RepoConfiguration config) {
        String infoLine = commitInfo.getInfoLine();
        String statLine = commitInfo.getStatLine();

        String[] elements = infoLine.split(LOG_SPLITTER, 6);
        String hash = elements[COMMIT_HASH_INDEX];
        Author author = config.getAuthor(elements[AUTHOR_INDEX], elements[EMAIL_INDEX]);

        Date date = null;
        try {
            date = GIT_STRICT_ISO_DATE_FORMAT.parse(elements[DATE_INDEX]);
        } catch (ParseException pe) {
            logger.log(Level.WARNING, "Unable to parse the date from git log result for commit.", pe);
        }

        String messageTitle = (elements.length > MESSAGE_TITLE_INDEX) ? elements[MESSAGE_TITLE_INDEX] : "";
        String messageBody = (elements.length > MESSAGE_BODY_INDEX)
                ? getCommitMessageBody(elements[MESSAGE_BODY_INDEX]) : "";
        int insertion = getInsertion(statLine);
        int deletion = getDeletion(statLine);

        String gitShowCommand = "git show %s";
        Path repoRootPath = Paths.get(config.getRepoRoot());
        GitShowResults commandResults = new GitShowResults(
                hash, elements[AUTHOR_INDEX], elements[EMAIL_INDEX], elements[DATE_INDEX], messageTitle, messageBody,
                runCommand(repoRootPath, String.format(gitShowCommand, hash))
        );
        FileUtil.writeJsonFile(commandResults, Paths.get(path, hash).toString());

        return new CommitResult(author, hash, date, messageTitle, messageBody, insertion, deletion);
    }

    private static String getCommitMessageBody(String raw) {
        Matcher matcher = MESSAGEBODY_LEADING_PATTERN.matcher(raw);
        return matcher.replaceAll("");
    }

    private static int getInsertion(String raw) {
        return getNumberWithPattern(raw, INSERTION_PATTERN);
    }

    private static int getDeletion(String raw) {
        return getNumberWithPattern(raw, DELETION_PATTERN);
    }

    private static int getNumberWithPattern(String raw, Pattern p) {
        Matcher m = p.matcher(raw);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}


class GitShowResults {
    private String hash;
    private String author;
    private String email;
    private String date;
    private String title;
    private String message;
    private ArrayList<Diff> diffs = new ArrayList<>();

    public GitShowResults(
            String hash, String author, String email, String date, String title, String message, String results
    ) {
        this.hash = hash;
        this.author = author;
        this.email = email;
        this.date = date;
        this.title = title;
        this.message = message;
        String[] rawDiffs = results.split("\ndiff --git ");
        for (int i = 1; i < rawDiffs.length; i++) {
            this.diffs.add(new Diff(rawDiffs[i]));
        }
    }
}

class Diff {
    private List<Line> hunks = new ArrayList<>();
    private String fileName;

    public Diff(String rawDiff) {
        String diffHeader = rawDiff.substring(0, rawDiff.indexOf("\n"));
        fileName = diffHeader.split(" ")[1].substring(2);

        String[] diffLines = rawDiff.split("\n");
        int i = 2;
        while (i < diffLines.length && !diffLines[i].startsWith("@")) {
            i++;
        }
        while (i < diffLines.length) {
            hunks.add(new Line(diffLines[i]));
            i++;
        }
    }
}


class Line {
    private int type;
    private String line;

    public Line(String line) {
        if (line.startsWith("@")) {
            this.type = 0;
        } else if (line.startsWith("+")) {
            this.type = 1;
        } else if (line.startsWith("-")) {
            this.type = 2;
        } else {
            this.type = 3;
        }
        this.line = line;
    }
}
